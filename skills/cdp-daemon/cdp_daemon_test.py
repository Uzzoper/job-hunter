#!/usr/bin/env python3
"""
cdp_daemon_test.py — issue #44 tests for cdp_daemon.py (persistent CDP daemon
for session reuse).

Covers:
  * WS frame codec (encode/decode round-trip, opcodes, extended length)
  * RFC6455 handshake against a fake socketpair CDP server (never real Chrome)
  * call/response id matching, ping->pong, close, timeout
  * CdpDaemon: health / submit / get_job shapes, queue FIFO ordering,
    auto-reconnect (re-list targets + re-open WS)
  * HTTP API JSON shapes + routing (in-process http.server, no OS process)
  * daemon-level handling of an unreachable CDP (queued job -> failed-retryable)

Plain unittest (pytest-compatible). Stdlib only. Mock socket / http.server
inputs; no real browsers, no subprocesses, no external network.
"""

import collections
import http.server
import json
import os
import queue
import socket
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cdp_daemon  # noqa: E402


# ---------------------------------------------------------------------------
# Fake socketpair-based CDP server (RFC6455 mini-server for tests only)
# ---------------------------------------------------------------------------

class FakeCdpServer:
    """A minimal WebSocket server over socket.socketpair() for unit tests.

    After handshake it reads client frames and replies from a scripted
    ``replies`` queue (the raw response bytes/dict to send). Pings are answered
    with pongs automatically. Received command frames are pushed to
    ``received``. ``client`` is the end handed to CdpWebSocket.
    """

    def __init__(self):
        self.server_sock, self.client = socket.socketpair()
        self.replies = queue.Queue()
        self.received = queue.Queue()
        self._stop = threading.Event()
        self.errors = []
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _read_until(self, marker):
        data = b""
        while marker not in data:
            chunk = self.server_sock.recv(4096)
            if not chunk:
                raise ConnectionError("socket closed during handshake read")
            data += chunk
        return data

    def _handshake(self):
        req = self._read_until(b"\r\n\r\n").decode("latin1")
        key = None
        for line in req.split("\r\n"):
            if line.lower().startswith("sec-websocket-key:"):
                key = line.split(":", 1)[1].strip()
        accept = cdp_daemon.compute_accept(key)
        self.server_sock.sendall((
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode())

    def _send_reply(self, reply):
        if isinstance(reply, dict):
            reply = json.dumps(reply).encode()
        elif isinstance(reply, str):
            reply = reply.encode()
        if reply:
            cdp_daemon.send_frame(self.server_sock, 0x1, reply, mask=False)

    def _serve(self):
        try:
            self._handshake()
            while not self._stop.is_set():
                opcode, payload = cdp_daemon.recv_frame(self.server_sock)
                if opcode == 0x9:  # ping -> pong
                    cdp_daemon.send_frame(self.server_sock, 0xA, payload, mask=False)
                    continue
                if opcode == 0x8:  # close
                    break
                if opcode == 0x1:
                    self.received.put(payload)
                    # Send any/really-all replies currently buffered, in order,
                    # so id-matching tests (wrong id then right id) get both
                    # frames back-to-back from this one request.
                    try:
                        reply = self.replies.get_nowait()
                    except queue.Empty:
                        reply = self.replies.get(timeout=3.0)
                    if reply:
                        self._send_reply(reply)
                    while True:
                        try:
                            self._send_reply(self.replies.get_nowait())
                        except queue.Empty:
                            break
        except Exception as exc:  # pragma: no cover - surfaced via errors for asserts
            if not self._stop.is_set():
                self.errors.append(exc)

    def stop(self):
        self._stop.set()
        try:
            self.server_sock.close()
        except OSError:
            pass
        try:
            self.client.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Frame codec (pure)
# ---------------------------------------------------------------------------

class FrameCodecTests(unittest.TestCase):
    """encode_frame / decode_frame round-trip for RFC6455 frames."""

    def test_text_frame_roundtrip(self):
        payload = b"Hello CDP"
        frame = cdp_daemon.encode_frame(0x1, payload, mask=True)
        opcode, decoded = cdp_daemon.decode_frame(frame)
        self.assertEqual(opcode, 0x1)
        self.assertEqual(decoded, payload)

    def test_server_unmasked_frame_decode(self):
        # Server frames are unmasked.
        payload = b"reply"
        frame = cdp_daemon.encode_frame(0x1, payload, mask=False)
        opcode, decoded = cdp_daemon.decode_frame(frame)
        self.assertEqual(opcode, 0x1)
        self.assertEqual(decoded, payload)

    def test_extended_length_126(self):
        payload = bytes(range(256)) * 4  # 1024 bytes > 125
        frame = cdp_daemon.encode_frame(0x1, payload, mask=True)
        opcode, decoded = cdp_daemon.decode_frame(frame)
        self.assertEqual(opcode, 0x1)
        self.assertEqual(decoded, payload)

    def test_ping_pong_opcodes(self):
        ping = cdp_daemon.encode_frame(0x9, b"ping-body", mask=False)
        self.assertEqual(cdp_daemon.decode_frame(ping)[0], 0x9)
        pong = cdp_daemon.encode_frame(0xA, b"ping-body", mask=False)
        self.assertEqual(cdp_daemon.decode_frame(pong)[0], 0xA)


# ---------------------------------------------------------------------------
# Handshake
# ---------------------------------------------------------------------------

class HandshakeTests(unittest.TestCase):
    """RFC6455 client handshake against the fake socketpair server."""

    def test_handshake_ok(self):
        server = FakeCdpServer()
        self.addCleanup(server.stop)
        ws = cdp_daemon.CdpWebSocket(
            server.client, "ws://localhost:9222/devtools/page/ABC"
        )
        self.assertIsNotNone(ws)

    def test_handshake_bad_accept_raises(self):
        # A server that answers the upgrade with a WRONG accept value must cause
        # the client to reject the handshake (RFC 6455 accept verification).
        server_sock, client = socket.socketpair()
        self.addCleanup(server_sock.close)
        self.addCleanup(client.close)

        def wrong():
            req = b""
            while b"\r\n\r\n" not in req:
                req += server_sock.recv(4096)
            server_sock.sendall((
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                "Sec-WebSocket-Accept: YmFkLWFjY2VwdA==\r\n\r\n"
            ).encode())
            try:
                while True:
                    opcode, _payload = cdp_daemon.recv_frame(server_sock)
                    if opcode == 0x8:
                        break
            except Exception:
                pass
            server_sock.close()

        thread = threading.Thread(target=wrong, daemon=True)
        thread.start()
        with self.assertRaises(ValueError):
            cdp_daemon.CdpWebSocket(
                client, "ws://localhost:9222/devtools/page/ABC"
            )

    def test_compute_accept_matches_rfc_sample(self):
        # RFC 6455 section 1.3 example: key "dGhlIHNhbXBsZSBub25jZQ==" ->
        # "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        self.assertEqual(
            cdp_daemon.compute_accept("dGhlIHNhbXBsZSBub25jZQ=="),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
        )


# ---------------------------------------------------------------------------
# call / id matching / ping / timeout
# ---------------------------------------------------------------------------

class CallTests(unittest.TestCase):
    """call() sends masked text frames and matches responses by id."""

    def _open_ws(self, server):
        return cdp_daemon.CdpWebSocket(
            server.client, "ws://localhost:9222/devtools/page/ABC"
        )

    def test_call_sends_and_matches_id(self):
        server = FakeCdpServer()
        self.addCleanup(server.stop)
        server.replies.put({"id": 1, "result": {"result": {"value": True}}})
        ws = self._open_ws(server)
        result = ws.call("Runtime.evaluate", {"expression": "1"})
        self.assertEqual(result, {"result": {"value": True}})
        # The server saw the (masked, decoded back) request frame.
        sent = json.loads(server.received.get(timeout=2.0))
        self.assertEqual(sent["id"], 1)
        self.assertEqual(sent["method"], "Runtime.evaluate")

    def test_call_ignores_unrelated_ids(self):
        server = FakeCdpServer()
        self.addCleanup(server.stop)
        # Send a response with a wrong id first, then the matching one.
        server.replies.put({"id": 999, "result": {"ignored": True}})
        server.replies.put({"id": 1, "result": {"ok": True}})
        ws = self._open_ws(server)
        result = ws.call("Page.getNavigationHistory")
        self.assertEqual(result, {"ok": True})

    def test_ping_receives_pong_live(self):
        server = FakeCdpServer()
        self.addCleanup(server.stop)
        server.replies.put({"id": 1, "result": {}})
        ws = self._open_ws(server)
        result = ws.call("Runtime.evaluate", {"expression": "1"})
        self.assertEqual(result, {})

    def test_call_timeout_raises(self):
        server = FakeCdpServer()
        self.addCleanup(server.stop)
        # No reply is scripted -> the call must time out.
        ws = self._open_ws(server)
        with self.assertRaises(TimeoutError):
            ws.call("Runtime.evaluate", {"expression": "1"}, timeout=0.15)


# ---------------------------------------------------------------------------
# Recording fake ws used by daemon queue/order tests
# ---------------------------------------------------------------------------

class RecordingWs:
    """A fake warm WS that records every call (method+params) in order."""

    def __init__(self):
        self.calls = []

    def call(self, method, params):
        self.calls.append((method, params))
        return {"result": {"value": True}}


# ---------------------------------------------------------------------------
# CdpDaemon — health / submit / get_job
# ---------------------------------------------------------------------------

class DaemonApiTests(unittest.TestCase):
    def test_health_shape_when_disconnected(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        health = d.health()
        self.assertEqual(health, {"cdp_connected": False, "queue_depth": 0})

    def test_submit_and_get_job(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        job_id = d.submit_job("https://jobs.gupy.io/jobs/1", [{"type": "fill_form"}])
        self.assertTrue(job_id.startswith("job-"))
        job = d.get_job(job_id)
        self.assertEqual(job["status"], "queued")
        self.assertIsNone(job["result"])

    def test_get_job_unknown_returns_none(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        self.assertIsNone(d.get_job("job-nope"))

    def test_worker_marks_failed_retryable_when_disconnected(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        d._ws = None  # no warm connection
        job_id = d.submit_job("https://jobs.gupy.io/jobs/1", [{"type": "fill_form"}])
        self.assertTrue(d._worker_step())
        job = d.get_job(job_id)
        self.assertEqual(job["status"], "failed")
        self.assertTrue(job["result"]["ok"] is False)
        self.assertTrue(job["result"]["retryable"])

    def test_queue_fifo_order(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        ws = RecordingWs()
        d._ws = ws
        for i in range(3):
            d.submit_job(f"url{i}", [{"selector": f"sel{i}"}])
        for _ in range(3):
            self.assertTrue(d._worker_step())
        # Each job's step was evaluated in FIFO submission order.
        expressions = [p["expression"] for (_m, p) in ws.calls]
        self.assertEqual(
            expressions,
            [json.dumps({"selector": "sel0"}),
             json.dumps({"selector": "sel1"}),
             json.dumps({"selector": "sel2"})],
        )

    def test_worker_sets_status_done(self):
        d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        d._ws = RecordingWs()
        job_id = d.submit_job("url", [{"type": "fill_form"}, {"type": "submit"}])
        self.assertTrue(d._worker_step())
        job = d.get_job(job_id)
        self.assertEqual(job["status"], "done")
        self.assertTrue(job["result"]["ok"])


# ---------------------------------------------------------------------------
# CdpDaemon — reconnect (re-list targets + re-open WS)
# ---------------------------------------------------------------------------

class ReconnectTests(unittest.TestCase):
    def _targets(self):
        return [{"type": "page",
                 "webSocketDebuggerUrl": "ws://localhost:9222/devtools/page/X"}]

    def test_try_connect_relists_targets_and_opens_ws(self):
        fetched = []
        opened = []

        def http_fetch(url, timeout):
            fetched.append(url)
            return self._targets()

        def ws_factory(ws_url, timeout):
            opened.append(ws_url)
            return RecordingWs()

        d = cdp_daemon.CdpDaemon(
            cdp_url="http://localhost:9222",
            ws_factory=ws_factory,
            http_fetch=http_fetch,
        )
        self.assertTrue(d._try_connect())
        self.assertTrue(d.is_connected())
        self.assertEqual(fetched, ["http://localhost:9222/json/list"])
        self.assertEqual(opened, ["ws://localhost:9222/devtools/page/X"])

    def test_reconnect_after_server_drop_relists(self):
        fetched = []
        opened = []

        def http_fetch(url, timeout):
            fetched.append(url)
            return self._targets()

        def ws_factory(ws_url, timeout):
            opened.append(ws_url)
            return RecordingWs()

        d = cdp_daemon.CdpDaemon(
            cdp_url="http://localhost:9222",
            ws_factory=ws_factory,
            http_fetch=http_fetch,
        )
        self.assertTrue(d._try_connect())
        # Simulate the reconnect loop detecting a dropped server.
        d._ws = None
        self.assertTrue(d._try_connect())
        self.assertEqual(len(opened), 2)
        self.assertEqual(len(fetched), 2)

    def test_try_connect_fails_when_no_page_target(self):
        d = cdp_daemon.CdpDaemon(
            cdp_url="http://localhost:9222",
            ws_factory=lambda url, t: RecordingWs(),
            http_fetch=lambda url, t: [],
        )
        self.assertFalse(d._try_connect())
        self.assertFalse(d.is_connected())

    def test_try_connect_fails_when_fetch_errors(self):
        def http_fetch(url, timeout):
            raise RuntimeError("CDP endpoint down")

        d = cdp_daemon.CdpDaemon(
            cdp_url="http://localhost:9222",
            ws_factory=lambda url, t: RecordingWs(),
            http_fetch=http_fetch,
        )
        self.assertFalse(d._try_connect())
        self.assertFalse(d.is_connected())


# ---------------------------------------------------------------------------
# HTTP API (in-process http.server, no OS process)
# ---------------------------------------------------------------------------

class HttpApiTests(unittest.TestCase):
    def setUp(self):
        self.d = cdp_daemon.CdpDaemon(cdp_url="http://localhost:9222")
        self.server = cdp_daemon.serve(self.d, port=0)
        self.port = self.server.server_address[1]
        self.addCleanup(self._stop_server)

    def _stop_server(self):
        self.server.shutdown()
        self.server.server_close()

    def _get(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}") as resp:
            return resp.status, json.loads(resp.read())

    def _post(self, path, body):
        data = json.dumps(body).encode()
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}", data=data, method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read())

    def test_health_route(self):
        status, health = self._get("/health")
        self.assertEqual(status, 200)
        self.assertEqual(set(health), {"cdp_connected", "queue_depth"})

    def test_submit_route(self):
        status, submitted = self._post("/jobs", {
            "job_url": "https://jobs.gupy.io/jobs/1", "action_plan": [],
        })
        self.assertEqual(status, 200)
        self.assertIn("job_id", submitted)

    def test_status_route(self):
        _s, submitted = self._post("/jobs", {
            "job_url": "u", "action_plan": [],
        })
        job_id = submitted["job_id"]
        status, job = self._get(f"/jobs/{job_id}")
        self.assertEqual(status, 200)
        self.assertIn("status", job)
        self.assertEqual(job["status"], "queued")

    def test_unknown_job_404(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get("/jobs/job-nope")
        self.assertEqual(ctx.exception.code, 404)

    def test_unknown_route_404(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._get("/nope")
        self.assertEqual(ctx.exception.code, 404)

    def test_submit_requires_job_url(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._post("/jobs", {"action_plan": []})
        self.assertEqual(ctx.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
