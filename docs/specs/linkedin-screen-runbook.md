# LinkedIn via Bot Screen — Runbook

How the job-hunter bot reads (and may apply to) LinkedIn jobs through the
official Hermes **Bot Screen** feature: live screen streaming of the bot's
own Xfce desktop with human takeover. This is an operational capability,
not Java code — nothing here changes the backend.

## 1. Requirements

- Hermes with Bot Screen support (≥ 0.21.5 — `hermes computer-use screen start`
  must exist; older binaries only have `install/status/doctor/permissions`).
- Gateway host runs Linux. Screen packages on the host (one-click **Install on
  host** in the Screen pane, or `hermes computer-use screen install`):
  `tigervnc-standalone-server xfce4-panel xfwm4 xfdesktop4 xfce4-settings
  xfce4-terminal dbus-x11 x11-xserver-utils x11-utils xauth fonts-dejavu-core`
  (Debian/Ubuntu).
- `computer_use` toolset enabled + cua-driver installed for the
  `jobhunter-bot` profile; `browser.headed: true`.
- Memory: Hermes refuses below `bot_desktop.min_free_memory_mb` (default
  1536). Measured: gateway idles ~300 MB, Xvnc+Xfce ~220 MB, headed Chromium
  0.5–1 GB. A 3.3 GB host fits screen + browser only with GUI apps closed
  (Firefox/Chrome/VS Code/Discord/Spotify are the big ones).
- Hermes Desktop (viewer) on the operator machine, connected to the host
  (same machine, SSH, or URL+token). Viewer only — the screen lives on the
  gateway host and survives closing the app or the laptop lid.

## 2. Setup (one time per host)

1. `hermes --version` → update until no update pending (`hermes update`).
2. `hermes computer-use screen install` (or Install on host in the pane).
3. Profile config: `computer_use` enabled, `browser.headed: true`. Nothing else.
4. `hermes -p jobhunter-bot computer-use screen start` → confirm `running`
   via `screen status` (holder expected: bot).
5. Desktop → BOTS → Jobhunter Bot → Screen box → watch. Take over / hand back
   as needed. A dropped connection keeps human control until reconnect +
   hand back (or Hand back (force)).

## 3. Login via takeover (LinkedIn)

1. Bot navigates to the jobs tab and stops at the login wall (it must NOT
   type credentials, create accounts, or solve CAPTCHAs alone).
2. Operator: Take over → log in with the designated account in the BOT's
   browser (its own Chromium profile/cookie jar) → Hand back.
3. Shortcut: the Desktop "Stay signed in to your sites" dialog
   (**Use my profile**) copies cookies from the operator's default browser —
   skips typing when already logged in there. Promises: live profile never
   opened directly, nothing leaves the machine.
4. Session persists in the bot's browser profile; later runs reuse it.

## 4. Standing LinkedIn rules (also saved in `skills/job-application/SKILL.md`)

1. Max 3 read runs/day, ≥ 2h apart, max 30 jobs/run.
2. On any checkpoint, CAPTCHA, identity verification or unexpected security
   screen: stop immediately, ask for human takeover. Never solve CAPTCHAs,
   never create accounts, never post/like/comment/message.
3. Read-only until ordered otherwise per job: extract and save, never apply
   or interact without explicit per-job instruction.
4. On any account restriction: report at once, pause ALL LinkedIn work until
   explicit release.
5. Strictly on-demand: LinkedIn work only on explicit chat request. Never
   create, suggest or run cron jobs / scheduled routines / automatic runs
   involving LinkedIn. (Verified: no scheduled routine touches LinkedIn;
   the dead Gupy backfill cron stays paused.)

## 5. Risk policy (read first)

- LinkedIn ToS prohibits automation. Enforcement ladder: checkpoint/CAPTCHA
  → temporary restriction → (repeated/heavy abuse) ban.
- Reading (spike): low risk. Automated Easy Apply: medium risk — the
  violation is the automation, not the application (employers see a normal
  application). Message/connection spam: high risk, actively punished —
  never do it.
- Keep applies human-paced (few/day, spaced, high-score only) behind the
  existing review gate. Never run unattended loops.
- Account strategy: a dedicated **reader account** (warmed up: complete
  profile, connections, days of normal browsing) isolates the personal
  profile for scraping. It still violates ToS (fake identity) and linkage
  via IP/device fingerprint is possible — mitigation, not immunity.
  Applications always go from the REAL account, human-supervised. Applying
  as a fake identity is fraud, not automation.

## 6. Proven state (spike 2026-09-24)

- Jobs search tab readable logged-in via bot browser + preview pane.
- 24 jobs extracted to `/tmp/linkedin_tab_spike.json` (title/company/
  location/model; 9 remote, 18 junior titles, 0 blocks) via accessibility
  snapshot — no clicks, no navigation.
- URLs missing: AX snapshot carries no hrefs; Playwright MCP was up on
  :8931 (systemd unit `hermes-playwright-mcp.service`) but stateless HTTP
  calls fail with "Server not initialized" (needs SSE session). URL
  strategy deferred to the LinkedIn-tab source spec (options: shared-cookie
  microservice, per-card click pass, other).
- `~/.hermes/tools` hosts the MCP server; port 8931 must be listening or
  the Desktop shows "playwright MCP failed its health check".

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Desktop dies: `GPU process isn't usable. Goodbye.` | No usable GPU in VM. Relaunch with `LIBGL_ALWAYS_SOFTWARE=1 ... -- --disable-gpu --disable-dev-shm-usage --no-sandbox`. Window may still render without flags; stability needs them. |
| `hermes:api` 404 `Session not found` | Desktop pointed at a backend/profile without that session; check connection + profile. |
| Screen pane: `Screen packages missing` | Install on host (one click) or the apt line. |
| Screen pane: `Not enough free memory` (X available of 3378 MB, 1536 needed) | Close GUI apps; check `free -m`. Temporary fallback: lower `bot_desktop.min_free_memory_mb` (default 1536, restore after). Never force with backend fetch running alongside. |
| No `screen start` in CLI | Hermes too old — update first. |
| `human_has_control` stuck after disconnect | Reconnect → Hand back (or force). CLI: `screen stop --force` releases. |
| Playwright MCP unhealthy | `systemctl --user is-active hermes-playwright-mcp`; port 8931 listening; logs without MODULE_NOT_FOUND. Old `[`-module / `--connect-timeout` errors were a stale launcher era — current unit uses `node cli.js --port 8931`. |
| Keyboard layout wrong in takeover | Screen runs US keymap; type passwords accordingly or `setxkbmap` on that DISPLAY. |
