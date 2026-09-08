# cdp-daemon — persistent CDP daemon for session reuse (issue #44)

## Purpose

`apply.py` (issue #39) recovers a *persistent* Chromium profile so the human's
GitHub/portal login survives between runs. Today each application invocation
opens a fresh CDP handshake to Chrome. The **CDP daemon** fixes the wasteful
part: it keeps **one warm WebSocket** to Chrome and exposes a tiny HTTP API that
accepts application plans and drains them over that warm session — no
per-application handshake, no repeated session recovery.

- **Stdlib only** (http.server, socket, hashlib, base64, urllib, threading,
  collections, argparse). No third-party WebSocket library and no gRPC.
- **Console messages / logs are English** (repo convention).
- **User-facing JSON `detail` strings are PT-BR** (relayed to the human by the
  bot), matching apply.py's convention.

## Architecture

```
bot / apply.py --POST /jobs-->  cdp_daemon  --masked text frames-->  Chrome CDP
        ^                            |                                    |
        |__ GET /health              |__ warm WS (single, reconnected)     |
                                     +__ FIFO job queue (in-memory)        |
                                     +__ worker thread pops jobs,          |
                                         runs Runtime.evaluate per step     |
```

- The daemon is an **HTTP server** (to the bot) and a **WebSocket client** (to
  Chrome). It never owns a WebSocket *server*.
- `navigation.py` / `ax_tree.py` stay **stateless** and never hold a CDP
  session; the daemon is the single warm-CDP owner.
- The daemon points at the same Chrome the bot already keeps alive
  (`--remote-debugging-port`), discovered via `GET {cdp_url}/json/list`.

## Files

| File | Role |
|---|---|
| `cdp_daemon.py` | WS client (`CdpWebSocket`), daemon (`CdpDaemon`), HTTP API, CLI (`main`) |
| `cdp_daemon_test.py` | RED->GREEN tests (frame codec, handshake, call/id/ping/timeout, daemon API, reconnect, in-process HTTP API) — no real browser |
| `jobhunter-cdp-daemon.service` | systemd **user** unit |
| `SKILL.md` | This document |

## HTTP API

Base URL: `http://127.0.0.1:19999` (loopback only). All responses JSON.

| Endpoint | Method | Request body | Response |
|---|---|---|---|
| `/health` | GET | — | `{"cdp_connected": bool, "queue_depth": int}` |
| `/jobs` | POST | `{"job_url": str, "action_plan": [step, ...]}` | `200 {"job_id": "job-N"}`; `400` if `job_url` missing |
| `/jobs/{id}` | GET | — | `200 {"status": "queued\|running\|done\|failed", "result": ...}`; `404` unknown |

- `queue_depth` reflects how many plans are waiting behind the current one
  (FIFO). The daemon drains them one at a time.
- On a `failed` job the `result` carries `{"ok": false, "retryable": true,
  "detail": <PT-BR>}` — the daemon never drops a queued job because the warm WS
  was briefly down; the reconnect loop refreshes the WS first.

## apply.py integration (thin)

`apply.py --daemon-url http://127.0.0.1:19999`:

1. `GET /health`. If the daemon is reachable **and** `cdp_connected`:
   - `POST /jobs` with the generated action plan;
   - the printed plan gains `"executor": {"via": "cdp-daemon",
     "daemon_url": ..., "job_id": "job-N"}` and is **still printed** (the bot
     decides whether to trust the daemon result or fall back).
2. Daemon unreachable **or** not connected:
   - falls back to the **direct plan** (same output as without the flag) plus a
     warning field, e.g.
     `"daemon_fallback": {"reason": "unreachable", "detail": <PT-BR>}`.
3. Flag absent → behavior unchanged.

This is deliberately **thin**: apply.py submits the plan and annotates it; it
does **not** implement retries, idempotent re-drive of a half-run session, or
multi-browser routing — those are non-goals (see below).

## Running it

Manual (foreground):

```bash
python3 skills/cdp-daemon/cdp_daemon.py --port 19999 --cdp-url http://localhost:9222
python3 apply.py --job-url '<job>' --profile <p.json> --daemon-url http://127.0.0.1:19999
```

CLI flags (English help):

```
--port PORT            HTTP port (default 19999)
--cdp-url URL          Chrome DevTools endpoint (default http://localhost:9222)
--user-data-dir DIR    note: the Chromium profile kept warm (default apply.py's)
```

Systemd user service (installed automatically by
`scripts/install-bot-skills.sh`, which copies the unit and runs
`daemon-reload`; enabling stays a manual opt-in step):

```bash
cp skills/cdp-daemon/jobhunter-cdp-daemon.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now jobhunter-cdp-daemon.service
# keep it alive after logout (matches the Hermes gateway bot):
loginctl enable-linger $USER
```

Diagnostics:

```bash
systemctl --user status jobhunter-cdp-daemon.service
journalctl --user -u jobhunter-cdp-daemon.service -f
curl -s http://127.0.0.1:19999/health
```

## Failure modes

| Symptom | Cause | Behaviour |
|---|---|---|
| `/health` → `{"cdp_connected": false}` | Chrome not started / target list empty | apply.py falls back to direct plan with a warning; daemon reconnect loop keeps retrying |
| Daemon process down | crash / unit stopped | apply.py `GET /health` fails → direct-plan fallback (never a hard error) |
| Job `failed` + `retryable` | warm WS dropped mid-run | result marked retryable with PT-BR detail; never silently lost |
| Chrome killed | user closed it | daemon re-lists `/json/list`, re-handshakes, warms with `Runtime.evaluate` (issue #39 recovers the profile) |

## Non-goals (owning issues)

- **No multi-browser / no auth handling** — selecting among targets and
  navigating login/auth flows belong to `skills/job-portal-browser/` (#40) and
  apply.py's session gate (#41). The daemon picks the first `type=="page"`
  target and just evaluates plan steps.
- **No per-job idempotency** — apply.py owns dedup/refusal/session safety
  (#27/#28/#41). The daemon is a fire-and-forget executor over a warm session.
- **No durable queue** — the job queue is in-memory (a daemon restart drains
  nothing). Durability is the bot's responsibility (it can re-checksum via
  `/jobs/{id}`/idempotency).

## Tests

```bash
cd skills/cdp-daemon && python3 -m unittest cdp_daemon_test
# plus the apply.py suite for the --daemon-url integration:
cd skills/job-application && python3 -m unittest apply_test
```

Tests never launch a real browser (Chromium is not installed here): the WS side
uses a `socketpair` fake CDP server and the connection side injects `ws_factory`
/ `http_fetch` fakes.
