# Bot Screen — Runbook

Watching and driving the job-hunter bot's work through the official Hermes
**Bot Screen** feature: live screen streaming of the bot's own per-profile
Xfce desktop, with human takeover. Source-agnostic — LinkedIn, Gupy,
InfoJobs and any future site the bot opens all appear on the same screen.
This is an operational capability, not Java code — nothing here changes
the backend.

## 1. What it is

Each bot gets its own Xfce desktop on the headless Linux gateway host
(Xvnc + TigerVNC, per profile). The bot's `computer_use` and headed browser
act on it; Hermes Desktop streams it live (noVNC/WebSocket) and the operator
can **take over** (login, 2FA, CAPTCHA, payment), then **hand back** — the
bot continues with the session just signed in to. The screen lives on the
gateway host: it survives closing the app or the laptop lid. Sessions,
cookies and browser profile persist per bot (`<HERMES_HOME>/bot-desktop/`,
`<HERMES_HOME>/bot-desktop/browser-profile`).

Screens are work surfaces, not security boundaries (same OS user; the
control lease is tool-level). Fine for our single-tenant host.

## 2. Requirements

- Hermes with Bot Screen support (≥ 0.21.5 — `hermes computer-use screen
  start` must exist; older binaries only have install/status/doctor).
- Gateway host runs Linux. Screen packages (one-click **Install on host**
  in the Screen pane, or `hermes computer-use screen install`):
  `tigervnc-standalone-server xfce4-panel xfwm4 xfdesktop4 xfce4-settings
  xfce4-terminal dbus-x11 x11-xserver-utils x11-utils xauth fonts-dejavu-core`
  (Debian/Ubuntu).
- `computer_use` toolset + cua-driver for the `jobhunter-bot` profile;
  `browser.headed: true`.
- Memory: Hermes refuses below `bot_desktop.min_free_memory_mb` (default
  1536). Measured: gateway idles ~300 MB, Xvnc+Xfce ~220 MB, headed
  Chromium 0.5–1 GB. A 3.3 GB host fits screen + browser only with GUI
  apps closed (Firefox/Chrome/VS Code/Discord/Spotify are the big ones).
  The check is conservative (assumes browser too); desktop-only needs ~220 MB.
- Hermes Desktop (viewer) connected to the host (same machine, SSH, or
  URL+token). Viewer only.

## 3. Setup (one time per host)

1. `hermes --version` → `hermes update` until nothing pending.
2. `hermes computer-use screen install` (or Install on host in the pane).
3. Profile config: `computer_use` enabled, `browser.headed: true`. Nothing else.
4. `hermes -p jobhunter-bot computer-use screen start` → confirm `running`
   via `screen status` (holder expected: bot). Auto-start alternative:
   `bot_desktop.auto_start: true` (starts on first `computer_use`/headed
   use; off by default).
5. Desktop → BOTS → Jobhunter Bot → Screen box → watch. Take over / hand
   back as needed. A dropped connection keeps human control until reconnect
   + hand back (or Hand back (force)); `screen stop --force` releases from CLI.

## 4. Standard flow (every site)

1. Bot navigates with its headed browser and works (read/extract/apply per
   its instructions). Operator watches the Screen pane (auto-raise option:
   right-click bot → Open Screen when the bot uses it).
2. On login wall, 2FA, CAPTCHA or payment: bot STOPS and asks for takeover
   (it must never type credentials, create accounts or solve CAPTCHAs alone).
3. Operator takes over, does the human step, hands back; bot continues with
   the signed-in session. Sessions/cookies persist for later runs.
4. Shortcut: the Desktop "Stay signed in to your sites" dialog
   (**Use my profile**) copies cookies from the operator's default browser
   into the bot's managed snapshot (live profile never opened directly,
   nothing leaves the machine).

## 5. Instances

### 5a. Gupy portal-apply (established)

The bot's existing portal-apply flow, now visible. Gates unchanged: score
cutoffs (≥70 auto path bot-side, 60–69 human review), eliminatory gate
always enforced, no hybrid/onsite. Screen adds login/CAPTCHA takeover and
live audit; it changes no rule.

### 5b. LinkedIn jobs tab (EXPERIMENTAL — probing limits)

Status: testing how far this goes. Read-only spike 2026-09-24: jobs search
tab readable logged-in, 24 jobs to `/tmp/linkedin_tab_spike.json`
(title/company/location/model; 9 remote, 18 junior titles, 0 blocks) via
accessibility snapshot, no clicks. URLs missing (AX tree has no hrefs;
Playwright MCP up on :8931 via `hermes-playwright-mcp.service` user unit
but stateless-HTTP calls fail "Server not initialized" — needs SSE
session). URL strategy deferred (shared-cookie microservice, per-card
pass, or other).

Standing bot rules (also in `skills/job-application/SKILL.md`, LinkedIn
Usage Rules — the skill is the enforced copy):
1. Max 3 read runs/day, ≥ 2h apart, max 30 jobs/run. Strictly on-demand:
   LinkedIn work only on explicit chat request — never cron/scheduled/auto.
2. Checkpoint/CAPTCHA/identity check/unexpected security screen → stop at
   once, ask for human takeover. Never solve, never create accounts, never
   post/like/comment/message.
3. Read-only until ordered per job. Never apply or interact without
   explicit per-job instruction.
4. Any account restriction → report at once, pause ALL LinkedIn work until
   explicit release.

Risk tiers: reading = low; automated Easy Apply = medium (the violation
is the automation, not the application — employers see a normal
application; keep human-paced, few/day, high-score, behind review gate);
message/connection spam = high, actively punished — never.
Account strategy: dedicated warmed-up reader account isolates the personal
profile (mitigation, not immunity — linkage via IP/device possible).
Applications always real-account, human-supervised; fake-identity applying
is fraud.

### 5c. Future sites (pattern)

Any new site (ATS `applyUrl`s, company portals) follows §4 + per-site rules
appended here: what is read vs applied, whose account, volume caps,
takeover triggers. No Java change required to watch a new site — only bot
instructions.

## 6. Proven state

- Screen running for `jobhunter-bot` (DISPLAY :20, 1440x900); Desktop
  v0.21.5 stable with software GL (`LIBGL_ALWAYS_SOFTWARE=1 ... --disable-gpu
  --disable-dev-shm-usage --no-sandbox`; bare launch dies with
  `GPU process isn't usable` on QEMU/virtio).
- Playwright MCP healthy via user service (serverInfo answered); Desktop
  banner cleared. Old `[`-module / `--connect-timeout` errors were a stale
  launcher era.
- Dead Gupy backfill cron found erroring nightly (missing script) → paused,
  preserved for resume.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Desktop dies: `GPU process isn't usable. Goodbye.` | No usable GPU in VM. Relaunch with software-GL flags above. |
| `hermes:api` 404 `Session not found` | Desktop pointed at a backend/profile without that session; check connection + profile. |
| Screen pane: `Screen packages missing` | Install on host (one click) or the apt line. |
| Screen pane: `Not enough free memory` (X of 3378 MB, 1536 needed) | Close GUI apps; `free -m`. Temporary fallback: lower `bot_desktop.min_free_memory_mb` (default 1536, restore after). Don't run backend fetch alongside. |
| No `screen start` in CLI | Hermes too old — update first. |
| `human_has_control` stuck after disconnect | Reconnect → Hand back (or force). CLI: `screen stop --force`. |
| Playwright MCP unhealthy | `systemctl --user is-active hermes-playwright-mcp`; port 8931 listening; logs clean. |
| Keyboard layout wrong in takeover | Screen runs US keymap; type passwords accordingly or `setxkbmap` on that DISPLAY. |
| BOTS tab vs profiles confusion | Bot = profile under the hood (`~/.hermes/profiles/jobhunter-bot/`); daily work lives in BOTS → Jobhunter Bot (chat, routines, Screen), not the settings profile switcher. |
