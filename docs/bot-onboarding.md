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
```

Export `HERMES_API_KEY=YOUR_HERMES_API_KEY` before running the backend.

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
- **Resume**: copy your CV into the profile and add the standing rule
  (see README "Resume attachment").

## 4. Run the gateway as a service

```bash
jobhunter-bot gateway install && jobhunter-bot gateway start
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
| `EMAIL_TOOL_MISSING` | himalaya missing/misconfigured for the profile |
| Backend warns about memory dir | normal without a bot profile; set `bot.memory.dir` if custom |
| Skill not found by the bot | re-run `scripts/install-bot-skills.sh`, check per-skill subdirs |

## References

- `README.md` — full backend setup, email-via-Hermes section
- `docs/specs/hermes-agent-integration.md` — gateway contract
- `docs/specs/bot-memory-sync.md` — `§` format, merge rules, write-back
- `docs/specs/bot-company-enrichment.md` — enrichment pipeline
- `docs/adr/001-multi-interface.md` — why Bot, TUI and REST API coexist
