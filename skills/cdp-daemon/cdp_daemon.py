#!/usr/bin/env python3
"""
cdp_daemon.py — persistent CDP daemon for session reuse (issue #44).

Keeps ONE warm WebSocket connection to Chrome (the persistent Chromium profile
started/recovered by apply.py's issue #39 flow) so the bot does not re-handshake
on every application. The daemon exposes a tiny HTTP API (stdlib http.server)
that accepts job application plans and drains them over the warm WS.

Architecture (bot -> daemon HTTP -> warm WS -> Chrome):
    bot / apply.py  --POST /jobs-->  cdp_daemon  --masked text frames-->  Chrome CDP

Stdlib only: http.server, socket, hashlib, base64, os, json, collections,
threading, time, urllib, argparse. No third-party WebSocket library and no
gRPC: the RFC6455 client handshake + frames are hand-rolled here.

Design notes:
  * navigation.py / ax_tree.py stay stateless and never own a CDP session; the
    daemon is the single warm-CDP owner.
  * HTTP transport only (no WebSocket *server*); the daemon is a WebSocket
    *client* to Chrome and an HTTP *server* to the bot.
  * Console messages/logs are in English; user-facing JSON `detail` strings
    (relayed to the human by the bot) are in PT-BR, per repo convention.

Usage:
    python3 cdp_daemon.py --port 19999 --cdp-url http://localhost:9222
"""

import argparse
import base64
import collections
import hashlib
import http.server
import json
import os
import socket
import sys
import threading
import time
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, List, Optional, Tuple

DEFAULT_PORT = 19999
DEFAULT_CDP_URL = "http://localhost:9222"

# RFC 6455 GUID used to compute the Sec-WebSocket-Accept value.
_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
_PAGE_TYPES = ("page",)

# PT-BR user-facing messages (relayed to the human by the bot).
_FAILED_RETRYABLE_DETAIL = (
    "Navegador indisponível; aplicação poderá ser reprocessada quando a "
    "conexão com o Chrome for restabelecida."
)


# ---------------------------------------------------------------------------
# RFC 6455 frame codec (pure, testable)
# ---------------------------------------------------------------------------

def compute_accept(key: str) -> str:
    """Return the Sec-WebSocket-Accept value for a client Sec-WebSocket-Key."""
    sha = hashlib.sha1((key + _WS_GUID).encode("utf-8")).digest()
    return base64.b64encode(sha).decode("ascii")


def encode_frame(opcode: int, payload: bytes, mask: bool = True) -> bytes:
    """Encode a single (FIN=1) WebSocket frame with the given opcode.

    Client-to-server frames are masked (``mask=True``, the default); server
    frames are unmasked (``mask=False``).
    """
    payload = bytes(payload or b"")
    length = len(payload)
    header = bytearray([0x80 | (opcode & 0x0F)])
    b1 = 0x80 if mask else 0x00
    if length < 126:
        header.append(b1 | length)
    elif length < 65536:
        header.append(b1 | 126)
        header += length.to_bytes(2, "big")
    else:
        header.append(b1 | 127)
        header += length.to_bytes(8, "big")
    if mask:
        mask_key = os.urandom(4)
        header += mask_key
        payload = bytes(payload[i] ^ mask_key[i % 4] for i in range(length))
    return bytes(header) + payload


def decode_frame(frame: bytes) -> Tuple[int, bytes]:
    """Decode a single complete WebSocket frame; return (opcode, payload).

    Handles extension lengths (126/127) and both masked/unmasked frames. The
    FIN bit is treated as set; fragmented messages are not produced by CDP.
    """
    b0 = frame[0]
    b1 = frame[1]
    opcode = b0 & 0x0F
    masked = bool(b1 & 0x80)
    length = b1 & 0x7F
    offset = 2
    if length == 126:
        length = int.from_bytes(frame[2:4], "big")
        offset = 4
    elif length == 127:
        length = int.from_bytes(frame[2:10], "big")
        offset = 10
    if masked:
        mask = frame[offset:offset + 4]
        offset += 4
        payload = bytes(frame[offset + i] ^ mask[i % 4] for i in range(length))
    else:
        payload = frame[offset:offset + length]
    return opcode, payload


def _read_exact(sock, n: int) -> bytes:
    """Read exactly n bytes from a socket, or raise ConnectionError on EOF."""
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("socket closed during read")
        data += chunk
    return data


def recv_frame(sock) -> Tuple[int, bytes]:
    """Read one complete WebSocket frame from a socket; return (opcode, payload)."""
    header = _read_exact(sock, 2)
    b1 = header[1]
    length = b1 & 0x7F
    masked = bool(b1 & 0x80)
    if length == 126:
        ext = _read_exact(sock, 2)
        length = int.from_bytes(ext, "big")
    elif length == 127:
        ext = _read_exact(sock, 8)
        length = int.from_bytes(ext, "big")
    if masked:
        mask = _read_exact(sock, 4)
    else:
        mask = None
    payload = _read_exact(sock, length)
    if mask is not None:
        payload = bytes(payload[i] ^ mask[i % 4] for i in range(length))
    return header[0] & 0x0F, payload


def send_frame(sock, opcode: int, payload: bytes, mask: bool = True) -> None:
    """Encode and send one WebSocket frame over the socket."""
    sock.sendall(encode_frame(opcode, payload, mask))


def _recv_http_response(sock) -> str:
    """Read an HTTP response header block (up to the blank line) as latin1 text."""
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("socket closed during HTTP response read")
        data += chunk
    return data.decode("latin1")


# ---------------------------------------------------------------------------
# CdpWebSocket — hand-rolled RFC6455 client
# ---------------------------------------------------------------------------

class CdpWebSocket:
    """A minimal RFC6455 WebSocket *client* connected to a Chrome DevTools page.

    Performs the HTTP Upgrade handshake on construction, then supports
    request/response ``call()`` with id matching, plus automatic ping->pong.
    Server frames are unmasked; client frames are masked (per the spec).
    """

    def __init__(self, sock, ws_url: str, timeout: float = 5.0):
        self.sock = sock
        self.ws_url = ws_url
        self.timeout = timeout
        self.sock.settimeout(timeout)
        self._next_id = 1
        self._handshake()

    def _handshake(self) -> None:
        parsed = urllib.parse.urlparse(self.ws_url)
        host = parsed.hostname or "localhost"
        port = parsed.port or (443 if parsed.scheme == "wss" else 80)
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        self.sock.sendall(request.encode("latin1"))
        response = _recv_http_response(self.sock)
        status_line = response.split("\r\n", 1)[0]
        if "101" not in status_line:
            raise ConnectionError(f"WebSocket upgrade failed: {status_line}")
        headers: Dict[str, str] = {}
        for line in response.split("\r\n")[1:]:
            if ":" in line:
                name, _, value = line.partition(":")
                headers[name.strip().lower()] = value.strip()
        expected = compute_accept(key)
        if headers.get("sec-websocket-accept") != expected:
            raise ValueError("WebSocket handshake accept mismatch")

    def call(self, method: str, params: Optional[Dict[str, Any]] = None,
             timeout: Optional[float] = None) -> Dict[str, Any]:
        """Send a CDP command and return the matching response ``result``.

        Frames with unrelated ids are skipped (defensive against out-of-order
        CDP events). A server ping is answered with a pong transparently.
        Raises TimeoutError when no matching response arrives in ``timeout``.
        """
        mid = self._next_id
        self._next_id += 1
        payload = json.dumps({"id": mid, "method": method, "params": params or {}})
        send_frame(self.sock, 0x1, payload.encode("utf-8"), mask=True)
        deadline = time.monotonic() + (timeout if timeout is not None else self.timeout)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"CDP call timed out for id {mid} (method {method})")
            self.sock.settimeout(min(remaining, 1.0))
            try:
                opcode, data = recv_frame(self.sock)
            except socket.timeout:
                continue
            if opcode == 0x1:  # text
                msg = json.loads(data)
                if msg.get("id") == mid:
                    return msg.get("result", {})
                # Unrelated event/response: skip and keep reading.
            elif opcode == 0x8:  # close
                raise ConnectionError("CDP WebSocket closed by server")
            elif opcode == 0x9:  # ping -> pong
                send_frame(self.sock, 0xA, data, mask=True)


# ---------------------------------------------------------------------------
# Default transport helpers (used when the bot does not inject fakes)
# ---------------------------------------------------------------------------

def _default_http_fetch(url: str, timeout: float) -> Any:
    """GET a CDP HTTP endpoint (e.g. /json/list) and return parsed JSON."""
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read())


def _default_ws_factory(ws_url: str, timeout: float) -> CdpWebSocket:
    """Open a TCP connection to Chrome and hand it to CdpWebSocket."""
    parsed = urllib.parse.urlparse(ws_url)
    host = parsed.hostname or "localhost"
    port = parsed.port or (443 if parsed.scheme == "wss" else 80)
    sock = socket.create_connection((host, port), timeout=timeout)
    return CdpWebSocket(sock, ws_url, timeout=timeout)


# ---------------------------------------------------------------------------
# CdpDaemon — warm WS ownership + FIFO job queue + HTTP API
# ---------------------------------------------------------------------------

class CdpDaemon:
    """Keeps one warm WS to Chrome and drains a FIFO job queue over it.

    Injectable ``ws_factory`` / ``http_fetch`` make the daemon fully testable
    without a real browser: tests stub the target list and hand back a fake WS.
    Background reconnect/worker threads are only started by ``start()``;
    ``_worker_step()`` / ``_try_connect()`` are also callable directly (used by
    tests and by the background loops).
    """

    def __init__(self, cdp_url: str = DEFAULT_CDP_URL,
                 user_data_dir: Optional[str] = None,
                 ws_factory: Optional[Callable] = None,
                 http_fetch: Optional[Callable] = None,
                 port: int = DEFAULT_PORT,
                 reconnect_interval: float = 1.0,
                 http_timeout: float = 3.0,
                 ws_timeout: float = 5.0):
        self.cdp_url = cdp_url.rstrip("/")
        self.user_data_dir = user_data_dir
        self.port = port
        self.reconnect_interval = reconnect_interval
        self.http_timeout = http_timeout
        self.ws_timeout = ws_timeout
        self.ws_factory = ws_factory or _default_ws_factory
        self.http_fetch = http_fetch or _default_http_fetch
        self._lock = threading.Lock()
        self._ws: Optional[CdpWebSocket] = None
        self._queue: collections.deque = collections.deque()
        self._jobs: Dict[str, Dict[str, Any]] = {}
        self._job_seq = 0
        self._running = False
        self._reconnect_thread: Optional[threading.Thread] = None
        self._worker_thread: Optional[threading.Thread] = None
        self._httpd: Optional[http.server.ThreadingHTTPServer] = None
        self._http_thread: Optional[threading.Thread] = None

    # -- connection ----------------------------------------------------------

    def _try_connect(self) -> bool:
        """Re-list CDP targets and open (or refresh) the warm WS."""
        try:
            targets = self.http_fetch(f"{self.cdp_url}/json/list", self.http_timeout)
            ws_url: Optional[str] = None
            for target in targets or []:
                if (target.get("type") in _PAGE_TYPES
                        and target.get("webSocketDebuggerUrl")):
                    ws_url = target["webSocketDebuggerUrl"]
                    break
            if not ws_url:
                with self._lock:
                    self._ws = None
                return False
            ws = self.ws_factory(ws_url, self.ws_timeout)
            ws.call("Runtime.evaluate", {"expression": "1"})  # warm check
            with self._lock:
                self._ws = ws
            return True
        except Exception:
            with self._lock:
                self._ws = None
            return False

    def is_connected(self) -> bool:
        with self._lock:
            return self._ws is not None

    def _reconnect_loop(self) -> None:
        while self._running:
            if not self.is_connected():
                self._try_connect()
            time.sleep(self.reconnect_interval)

    # -- queue ---------------------------------------------------------------

    def submit_job(self, job_url: str, action_plan: Optional[List[Dict[str, Any]]]) -> str:
        """Enqueue an application plan; return a fresh job_id (FIFO)."""
        with self._lock:
            self._job_seq += 1
            job_id = f"job-{self._job_seq}"
            self._jobs[job_id] = {
                "status": "queued",
                "result": None,
                "job_url": job_url,
                "plan": action_plan,
            }
            self._queue.append(job_id)
            return job_id

    def get_job(self, job_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            return {"status": job["status"], "result": job["result"]}

    def health(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "cdp_connected": self._ws is not None,
                "queue_depth": len(self._queue),
            }

    def _worker_step(self) -> bool:
        """Pop one queued job (FIFO) and execute it over the warm WS.

        Returns True when a job was processed. If the warm WS is unavailable the
        job is marked ``failed`` + ``retryable`` (queued jobs wait for the
        reconnect loop instead of being dropped).
        """
        with self._lock:
            if not self._queue:
                return False
            job_id = self._queue.popleft()
            job = self._jobs[job_id]
            job["status"] = "running"
            ws = self._ws
            plan = job["plan"]
        if ws is None:
            with self._lock:
                job["status"] = "failed"
                job["result"] = {"ok": False, "retryable": True,
                                 "detail": _FAILED_RETRYABLE_DETAIL}
            return True
        try:
            for step in plan if isinstance(plan, list) else []:
                ws.call("Runtime.evaluate", {"expression": json.dumps(step, ensure_ascii=False)})
            with self._lock:
                job["status"] = "done"
                job["result"] = {"ok": True}
            return True
        except Exception as exc:
            with self._lock:
                job["status"] = "failed"
                job["result"] = {"ok": False, "retryable": True,
                                 "detail": f"Falha ao executar aplicação: {exc}"}
            return True

    def _worker_loop(self) -> None:
        while self._running:
            try:
                self._worker_step()
            except Exception:
                pass
            time.sleep(0.05)

    # -- lifecycle -----------------------------------------------------------

    def start(self) -> None:
        """Start the reconnect/worker threads and the in-process HTTP server."""
        if self._running:
            return
        self._running = True
        self._try_connect()
        self._worker_thread = threading.Thread(target=self._worker_loop, daemon=True)
        self._reconnect_thread = threading.Thread(target=self._reconnect_loop, daemon=True)
        self._worker_thread.start()
        self._reconnect_thread.start()
        self._httpd = serve(self, self.port)
        self.port = self._httpd.server_address[1]

    def stop(self) -> None:
        self._running = False
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
        with self._lock:
            self._ws = None


# ---------------------------------------------------------------------------
# HTTP API dispatch + handler
# ---------------------------------------------------------------------------

def handle_request(daemon: CdpDaemon, method: str, path: str,
                   body: bytes = b"") -> Tuple[int, Dict[str, Any]]:
    """Route a request to the daemon; return (status_code, json_dict)."""
    try:
        if method == "GET" and path == "/health":
            return 200, daemon.health()
        if method == "POST" and path == "/jobs":
            data = json.loads(body or b"{}")
            job_url = data.get("job_url")
            if not job_url:
                return 400, {"error": "invalid_request",
                             "detail": "campo 'job_url' é obrigatório"}
            job_id = daemon.submit_job(job_url, data.get("action_plan"))
            return 200, {"job_id": job_id}
        if method == "GET" and path.startswith("/jobs/"):
            job_id = path[len("/jobs/"):]
            job = daemon.get_job(job_id)
            if job is None:
                return 404, {"error": "not_found", "detail": "trabalho não encontrado"}
            return 200, job
        return 404, {"error": "not_found", "detail": "rota não encontrada"}
    except Exception as exc:
        return 500, {"error": "internal_error", "detail": str(exc)}


class _Handler(http.server.BaseHTTPRequestHandler):
    """HTTP/JSON adapter around CdpDaemon.handle_request()."""

    def _serve(self) -> None:
        daemon = getattr(self.server, "daemon", None)
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length) if length else b""
        status, payload = handle_request(daemon, self.command, self.path, body)
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = _serve
    do_POST = _serve

    def log_message(self, *args):  # keep daemon logs tidy (English, console)
        pass


def serve(daemon: CdpDaemon, port: int = 0) -> http.server.ThreadingHTTPServer:
    """Start an in-process HTTP server for the daemon; return the server."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    server.daemon = daemon
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    daemon._httpd = server
    daemon._http_thread = thread
    return server


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    """Run the daemon; blocking until interrupted. Console messages in English."""
    parser = argparse.ArgumentParser(
        prog="cdp_daemon.py",
        description="Persistent CDP daemon for session reuse (issue #44).",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"HTTP port to listen on (default: {DEFAULT_PORT})")
    parser.add_argument("--cdp-url", default=DEFAULT_CDP_URL,
                        help=f"Chrome DevTools endpoint (default: {DEFAULT_CDP_URL})")
    parser.add_argument("--user-data-dir", default=None,
                        help="Note: the Chromium profile dir kept warm by this daemon; "
                             "apply.py owns browser recovery (issue #39).")
    args = parser.parse_args(argv)

    daemon = CdpDaemon(cdp_url=args.cdp_url, user_data_dir=args.user_data_dir,
                       port=args.port)
    daemon.start()
    print(f"cdp-daemon listening on http://127.0.0.1:{daemon.port} "
          f"(cdp-url={daemon.cdp_url}, user-data-dir={args.user_data_dir or 'default'})")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("cdp-daemon stopping...")
        daemon.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
