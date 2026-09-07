# Bot onboarding — from fresh clone to working Hermes integration

Checklist for anyone cloning this repo who wants the full bot integration
(skills, memory sync, AI/email via the Hermes gateway). Backend-only usage
needs just steps 1–2; everything bot-related starts at step 3.

## 1. Clone and backend config

```bash
git clone https://github.com/Uzzoper/job-hunter.git
cd job-hunter
git checkout feat/bot-improvement   # bot track (until merged)
```

Prerequisites: Java 21, `python3` (skills are stdlib-only, no pip needed).

Create `src/main/resources/application-local.yaml` (gitignored, never commit):

```yaml
ai:
  provider: hermes          # or openrouter / ollama for bot-free usage
hermes:
  base-url: http://localhost:9119/v1   # MUST end in /v1
  api-key: YOUR_HERMES_API_KEY         # equals the gateway API_SERVER_KEY
  model: default
  timeout-seconds: 120
bot:
  memory:
    dir: ${user.home}/.hermes/profiles/jobhunter-bot/memories
  service:
    # Static service token for bot API access (issue #47). Bot calls send it
    # via the X-Bot-Token header; CLI/webapp keep the normal JWT login.
    # The SAME value must be saved later in the bot profile (api-token.txt).
    api-key: ${BOT_SERVICE_API_KEY}         # blank = bot token auth disabled
    owner-user-id: ${BOT_SERVICE_OWNER_USER_ID}  # existing user the bot acts as
```

Export `HERMES_API_KEY=YOUR_HERMES_API_KEY` before running the backend.

**Service token (optional — for bot API access, issue #47):** the bot
authenticates to the Job Hunter API with a static token via the
`X-Bot-Token` header, while CLI/webapp keep the usual JWT `Bearer` header
(login). Generate the secret once and set both sides to the SAME value:
`bot.service.api-key` here (env `BOT_SERVICE_API_KEY`) and later in the bot
profile as `api-token.txt` (step 3 / step 5). `owner-user-id` must be a
positive id (the `userId` from `POST /api/auth/login`) for the feature to
engage; blank `api-key` keeps the old JWT-only behavior.

**Shortcut (recommended):** `bash scripts/setup-bot-access.sh --owner-id <id>`
automates secret generation + profile save + backend exports + endpoint
verification in one go (see its `--help`). Backend restart stays manual.

**First-run bootstrap (bot self-guides, issue #46/#47):** when the bot reports
`missing_api_token` it generates the secret, saves it to
`~/.hermes/profiles/jobhunter-bot/api-token.txt`, and shows you the snippet
above. Human steps (bounded to paste-secret + restart):

- [ ] Copy `api-key` / `owner-user-id` from the bot's snippet into `bot.service.*` (or export `BOT_SERVICE_API_KEY` / `BOT_SERVICE_OWNER_USER_ID`).
- [ ] Restart the backend.
- [ ] Tell the bot "done" — its `X-Bot-Token` probe expects HTTP 200.
- [ ] Afterwards the bot runs fully autonomously, including self-updating skills via `scripts/install-bot-skills.sh`.

Bot-side procedure: `skills/job-application/SKILL.md` → "First-run bootstrap".

## 2. Verify backend-only mode (no bot required)

The backend boots and works without any bot files — memory sync logs a
warning and continues:

```bash
mvn -Dtest=BotMemorySyncServiceTest test   # memory sync unit tests
python3 skills/company-scraper/scraper_test.py
python3 skills/job-application/apply_test.py
```

## 3. Create the bot profile

```bash
hermes profile create jobhunter-bot --clone-all
```

Then, inside `~/.hermes/profiles/jobhunter-bot/`:

- **Email tool**: install the [himalaya](https://github.com/pimalaya/himalaya)
  CLI v2 and configure `~/.config/himalaya/config.toml` (password resolved
  from the profile `.env`, never duplicated).
- **Standing instruction** in `SOUL.md`: send with
  `himalaya message compose --send --attach YourName.pdf` and reply only
  `EMAIL_SENT` / `EMAIL_TOOL_MISSING`.
- **Gateway keys** in the profile `.env`:
  `API_SERVER_KEY=YOUR_HERMES_API_KEY`, `API_SERVER_PORT=9119`.
- **Approvals off** in the profile `config.yaml`
  (`approvals.mode: off`) — an interactive prompt would stall requests.
  Add it manually and verify:
  ```yaml
  approvals:
    mode: off
  ```
  ```bash
  grep -A1 '^approvals:' ~/.hermes/profiles/jobhunter-bot/config.yaml
  ```
- **Resume**: copy your CV into the profile and add the standing rule
  (see README "Resume attachment").

## 4. Run the gateway as a service

```bash
hermes gateway install --profile jobhunter-bot
hermes gateway start --profile jobhunter-bot
loginctl enable-linger        # keeps the unit alive without an active session
journalctl --user -u hermes-gateway-jobhunter-bot -f
```

Sanity check from another terminal:

```bash
curl -s http://localhost:9119/v1/models \
  -H "Authorization: Bearer $HERMES_API_KEY" | head -c 300
```

## 5. Install the skills into the bot profile

```bash
bash scripts/install-bot-skills.sh
# custom profile location:
HERMES_BASE=/custom/path bash scripts/install-bot-skills.sh
```

Expected layout afterwards:

```
~/.hermes/profiles/jobhunter-bot/
├── skills/
│   ├── company-scraper/      # company research (issue #32, extended #36)
│   ├── job-portal-browser/   # structured browsing fallback (#34)
│   ├── report-generator/     # progress reports (#35)
│   ├── analyzer/             # pattern analysis (#35)
│   ├── visualizer/           # funnel charts (#35)
│   └── job-application/      # Gupy apply planner (#37)
└── memails/                  # enrichment + application records (created empty)
```

## 6. Verify memory sync end to end (#31)

1. Tell the bot a preference in chat (e.g. "only remote roles").
2. Confirm a new `§` section appears in `memories/MEMORY.md`.
3. Restart the backend and check the log for the memory-merge line;
   `GET /api/profile` should reflect the merged preference.
4. Reject a role for a stated reason and confirm a new section is
   appended back to `MEMORY.md`.

## 7. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `404` from the gateway | `base-url` missing the `/v1` suffix |
| Requests hang until timeout | `approvals.mode` not `off` in profile `config.yaml` |
| `401` / empty replies | `HERMES_API_KEY` differs from profile `API_SERVER_KEY` |
| `401` on bot API calls (with a token) | `bot.service.api-key` on the backend differs from the profile's `api-token.txt` / `JOBHUNTER_API_TOKEN`, or `BOT_SERVICE_OWNER_USER_ID` is blank/0 so the filter is disabled | Compare both secrets (they must be the SAME value); confirm `BOT_SERVICE_OWNER_USER_ID` is a positive id |
| `EMAIL_TOOL_MISSING` | himalaya missing/misconfigured for the profile |
| Backend warns about memory dir | normal without a bot profile; set `bot.memory.dir` if custom |
| Skill not found by the bot | re-run `scripts/install-bot-skills.sh`, check per-skill subdirs |

## 8. CDP setup for automated Gupy applications (optional, advanced)

Gupy requires authentication to submit applications. The bot uses Hermes'
Browser Use, but you can improve the experience by sharing your local
Chromium session via CDP (Chrome DevTools Protocol). Skip this section
unless you use the `job-application` skill (#37) against Gupy.

### Why CDP?

- Bot logs into Gupy once via CDP.
- All subsequent applications reuse your logged-in session.
- No need to re-authenticate for each application.

### Setup

1. Install Chromium (if not present):
   ```bash
   sudo apt install chromium-browser
   ```
2. Start Chromium with remote debugging:
   ```bash
   chromium --remote-debugging-port=9222 --user-data-dir=~/.chromium-profile-cdp
   ```
3. Log into Gupy manually in this Chromium window (keep it open).
4. Point Hermes at the CDP endpoint:
   ```bash
   hermes config set browser.cdp_url "http://localhost:9222" --profile jobhunter-bot
   ```
5. Restart the gateway to pick up the new config:
   ```bash
   hermes gateway restart --profile jobhunter-bot
   ```
6. Verify CDP is working:
   ```bash
   curl -s http://localhost:9222/json/list | python3 -m json.tool
   ```
   You should see the Gupy tab listed.

### Troubleshooting CDP

| Symptom | Solution |
|---|---|
| CDP returns connection refused | Kill existing Chromium, restart with `--remote-debugging-port=9222` |
| "Opening in existing browser session" | Use a fresh `--user-data-dir` like `~/.chromium-profile-cdp` |
| Browser tool ignores CDP | Verify `browser.cdp_url` in profile config, restart gateway |
| Gupy shows login screen | Log into Gupy in the Chromium window, don't close it |

## References

- `README.md` — full backend setup, email-via-Hermes section
- `docs/specs/hermes-agent-integration.md` — gateway contract
- `docs/specs/bot-memory-sync.md` — `§` format, merge rules, write-back
- `docs/specs/bot-company-enrichment.md` — enrichment pipeline
- `docs/adr/001-multi-interface.md` — why Bot, TUI and REST API coexist
