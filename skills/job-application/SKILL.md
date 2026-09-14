# Skill: Job Application (Gupy + InfoJobs, structured)

> Hermes Agent skill for applying to job listings via a structured, planner-driven workflow.
> Designed for the `jobhunter-bot` profile. v2 supports Gupy + InfoJobs (issue #45).

---

## Purpose

Plan a job application as a **selector-free executor INTENT** that the bot executes
through its browser tool against live page snapshots. The intent is produced by
`apply.py` (stdlib-only Python), which validates inputs, enforces apply
guardrails, and emits `{intent_id, job_id, job_url, portal, profile, policy,
metadata}` — no steps, no CSS selectors, no `fill_form`. The MCP executor derives
every action from live AX snapshots via `classify.py` and records the outcome
via `verdict.py` (the sole writer of the applied record).

The legacy selector layer (portals `YAML`, `helpers/` Python modules) and the
CDP daemon were retired in cutover package 3 (`docs/specs/mcp-apply-loop.md`,
l.291-299): the intent contract is portal-neutral, so both portals share one
loop.

This is the **structured counterpart** to the free-form `job-portal-browser` navigation skill:

| Situation | Skill |
|---|---|
| Application flow known in advance (Gupy form fields are stable, predictable) | `job-application` (this skill) — planner emits intent, executor loop drives the browser |
| Unknown / free navigation, status checking, JS-only listing boards | `job-portal-browser` — bot drives freely |

---

## API-first flow (issue #46)

**The Job Hunter REST API is the PRIMARY job source.** Instead of receiving a
job URL from elsewhere, `apply.py` can pick the job straight from the backend:
top-scored from `GET /api/jobs` (`--from-api`) or by id from
`GET /api/jobs/{id}` (`--job-id <id>`, which prefills `job_url` +
`job_title`/`job_company`/`backend_job_id` metadata into the intent).

### One-time service-token setup (never store credentials in the repo)

The bot authenticates to the backend with the **static service token** (issue
#47, `BotTokenFilter`): the bot sends it via the `X-Bot-Token` header and the
backend matches it against `bot.service.api-key`. Bot calls use `X-Bot-Token`
only — no `Bearer` header, no user login. The token does not expire; rotation
= change `bot.service.api-key` and restart the backend.

To enable it once:

1. **Generate a secret** (same value goes on both sides):
   ```bash
   openssl rand -hex 24
   ```
2. **Set it on the backend** in `application-local.yaml`:
   ```yaml
   bot:
     service:
       api-key: ${BOT_SERVICE_API_KEY}            # X-Bot-Token must match exactly
       owner-user-id: ${BOT_SERVICE_OWNER_USER_ID}  # existing user the bot acts as (positive id)
   ```
   and export before running: `export BOT_SERVICE_API_KEY=<secret>`,
   `export BOT_SERVICE_OWNER_USER_ID=<your user id>` (the `userId` from
   `POST /api/auth/login`). Blank `api-key` disables the feature → bot calls
   fall through to the JWT chain and answer 401.
3. **Save the SAME value in bot memory** (so no secret ever lands in a git
   repo): `~/.hermes/profiles/jobhunter-bot/api-token.txt` — or export
   `JOBHUNTER_API_TOKEN=<secret>` on the bot host as an alternative.

The service token is a plain static key — it identifies the *bot*, scoped by
the backend to the configured `owner-user-id` (every request runs as that
user). Missing/401 semantics are unchanged: a missing token or a 401 (secret
mismatch) map to clean JSON and never fall back to storing human credentials.

### First-run bootstrap (the bot self-guides)

On a fresh host `resolve_token()` returns `missing_api_token`. Instead of
improvising, the bot runs this fixed bootstrap once and never asks again:

1. **Generate the secret** — `openssl rand -hex 24`.
2. **Save it to bot memory** — write it to
   `~/.hermes/profiles/jobhunter-bot/api-token.txt` (chmod 600), the exact
   file `resolve_token()` reads from.
3. **Show the human the backend step** — print this snippet and ask them to
   paste the secret + owner id, then restart the backend:
   ```yaml
   bot:
     service:
       api-key: <generated secret>
       owner-user-id: <your user id>   # from POST /api/auth/login
   ```
   (env form: `export BOT_SERVICE_API_KEY=<secret> BOT_SERVICE_OWNER_USER_ID=<id>`, then restart the service.)
4. **Apply backend side** — plain Java setups: the human pastes secret +
   owner id and restarts (operator territory, deliberately NOT bot actions).
   Docker setups: `bash scripts/setup-bot-access.sh --compose-dir DIR
   --recreate-backend` syncs the override env, recreates the backend, waits
   healthy and verifies — full auto once the human passes the flags.
5. **Verify** — when the human confirms, probe once:
   `curl -s http://localhost:8080/api/jobs?hasEmail=true
   -H "X-Bot-Token: <secret>"` — HTTP 200 means the token works.
6. **Full autonomy after** — the same secret serves every list/fetch/detail
   call; on later runs the bot can also self-update its skills via
   `bash scripts/install-bot-skills.sh`.

### Token resolution order

`job_api.resolve_token()` tries, in order:

1. `--api-token` flag,
2. `JOBHUNTER_API_TOKEN` environment variable,
3. `<profile-dir>/api-token.txt` — `--profile-dir` overrides the default
   `~/.hermes/profiles/jobhunter-bot`.

Missing → clean JSON `{"error": "missing_api_token", "detail": <pt-br
service-token setup + save step>}` (exit 1).

### Commands

```bash
# Plan from the top-scored job in the API (empty list → auto-fetch gupy → re-list):
python3 skills/job-application/apply.py --from-api \
  --profile profile.json --portal gupy --memory-dir <memails-dir> \
  [--min-score 60] [--no-fetch-if-empty] [--api-base-url http://localhost:8080]

# Plan from a specific job id (prefills job_url/title/company into the intent):
python3 skills/job-application/apply.py --job-id 7 \
  --profile profile.json --portal gupy --memory-dir <memails-dir>
```

New flags (all optional): `--job-id`, `--from-api`, `--api-base-url` (default
`http://localhost:8080`), `--api-token`, `--profile-dir`, `--min-score`,
`--fetch-if-empty` (default True) / `--no-fetch-if-empty`.

- `--fetch-if-empty` (default **True**): when `GET /api/jobs` returns empty the
  orchestrator triggers `POST /api/jobs/fetch/gupy` and re-lists.
- `--min-score` is forwarded as the real `minScore` query param; `hasEmail=true`
  is always sent by default (only jobs with a contact email are considered).
- `--job-id` takes precedence when both `--job-id` and `--from-api` are given.
- The classic `--job-url` flow emits the same intent envelope when no API flag
  is supplied (no `metadata` block is attached).
- Every API failure (missing token, 401, unknown job, unreachable host) prints
  clean JSON and exits 1 — never a traceback. A 401 maps to
  `{"error": "unauthorized"}`.

---

## When to use

- The portal is **Gupy** or **InfoJobs** and the job URL follows the portal's pattern (`https://<portal>.gupy.io/jobs/<id-slug>` or `https://www.infojobs.com.br/vaga/<id-slug>`).
- The bot has structured application data (name, email, phone, resume path, cover text) from the app profile.
- The user explicitly asked the bot to apply, and the confirmation protocol below can be honored.
- The job to apply to was picked from the **Job Hunter API** (issue #46) via `--from-api` or `--job-id`.

### When NOT to use

- The application is sent **by email** — use the himalaya email tool / `company-scraper` skill instead.
- The flow is unknown, multi-step/paginated, or needs login/session as the normal path (the loop assumes logged-in; a login page stops the loop with the hard `stop_on_auth_url` policy and hands off to the human — it does **not** silently retry). Use `job-portal-browser` for unknown/free navigation.
- The portal is **not** Gupy or InfoJobs — `apply.py` errors cleanly (`unknown_portal`). **LinkedIn is explicitly OUT of scope for automation**: LinkedIn applications are done **manually** by the human only; the separate LinkedIn scraper microservice (Node.js) only *reads* listings and never submits applications. There is deliberately **no** `linkedin` portal in the allow-list — requesting `--portal linkedin` errors cleanly.
- The paired draft is a `NO_APPLY` refusal — the bot must refuse to plan (guardrail #28).

---

## Inputs

| Parameter | Via flag | Type | Required | Description |
|---|---|---|---|---|
| `job-url` | `--job-url` | string | no * | Job URL (id derived from the portal's URL pattern). Required only when no API flag is used |
| API source | `--from-api` / `--job-id <id>` | flag / int | no | Issue #46: pick the top-scored job from the Job Hunter API, or a specific job detail (`GET /api/jobs/{id}` — prefills `job_url` + `job_title`/`job_company`/`backend_job_id` metadata) |
| API config | `--api-base-url` / `--api-token` / `--profile-dir` | string | no | Issue #46: backend base URL (default `http://localhost:8080`), token flag, and bot profile dir holding `api-token.txt` (default `~/.hermes/profiles/jobhunter-bot`) |
| API filters | `--min-score` / `--fetch-if-empty` | int / flag | no | Issue #46: `minScore` query filter; trigger a fetch (default gupy) when the list is empty — `--no-fetch-if-empty` disables it |
| profile | `--profile` | JSON file path | yes | `{name, email, phone, cv_path, cover_text}` (+ optional `no_apply: true`) |
| portal | `--portal` | string | no (default `gupy`) | `gupy` \| `infojobs` (allow-list in `intent.py`; unknown values error cleanly — **`linkedin` is NOT supported, by design**) |
| memory dir | `--memory-dir` | dir path | no | Base for idempotency records; default `~/.hermes/profiles/jobhunter-bot/memails` |
| affinity flags | `--confirmed`, `--auto-apply`, `--dry-run` | flags | no | See Confirmation protocol + Recording below |
| session/guard flags | `--current-url`, `--visited-urls`, `--skip-session-check` | string / flag | no | Issue #41: current page (session expiry pre-flight gate) and visited history (kept for the executor); `--skip-session-check` skips the gate (also skipped on `--dry-run`) |
| browser recovery | `--cdp-url`, `--user-data-dir` | URL / dir path | no | Issue #39: CDP endpoint (default `http://localhost:9222`, also the endpoint the Playwright MCP server attaches to via `--cdp-endpoint`) and persistent Chromium profile dir (default `~/.chromium-profile-cdp`) |
| status check | `--check-browser` | flag | no | Issue #39: print browser status as JSON and exit — no intent emitted |
| verification | `--verify-with` | string | no (default `screenshot`) | Issue #43: kept as an accepted executor hint (`screenshot` or `ax`); invalid modes error cleanly |
| intent mode | `--emit-intent`, `--max-steps` | flag / int | no | mcp-apply-loop: explicit intent-emission switch (already the **default** output); `--max-steps` caps the executor loop budget (default `25`) |

Profile JSON:

```json
{
  "name": "Juan Antonio Peruzzo",
  "email": "juan@example.com",
  "phone": "+55 42 99833-1363",
  "cv_path": "/home/juan/cv.pdf",
  "cover_text": "Olá! Gostaria de me candidatar à vaga."
}
```

---

## Steps

1. **Plan** — the bot runs `apply.py` with the job URL (or API flags), profile, and portal:
   ```
   python3 skills/job-application/apply.py \
     --job-url "https://jobs.gupy.io/jobs/<id-slug>" \
     --profile profile.json --portal gupy --memory-dir <memails-dir>
   ```
2. **Validate** — apply.py runs the pre-flight gates (order below): portal allow-list → profile validation (`name`, `email`, `phone`, `cv_path`, `cover_text`) → refusal #28 → API resolution → browser recovery #39 (skipped on `--dry-run`) → session expiry #41 → idempotency #27. Any failure returns a JSON error.
3. **Receive the intent envelope** — `{"intent": {...}, "dry_run": true}` (the `--dry-run` hint lives OUTSIDE the intent object, spec l.111). The intent carries `{intent_id, job_id, job_url, portal, profile, policy, metadata?}` — no steps, no selectors.
4. **Execute the MCP loop** — snapshot-driven against live AX trees (see "MCP executor apply loop" below).

Pre-flight gate order in `apply.py` (cutover pkg 3 — the deterministic #38
guard and the legacy session-expired *plan* are gone; the executor enforces
auth/loop stops per step at runtime):

```
portal allow-list (intent.is_supported_portal)
  → profile validation
  → refusal (#28)
  → browser recovery (#39, non --dry-run)
  → session expiry (#41)     ← expired → session_expired error (exit 0)
  → idempotency (#27)
  → intent emission
```

---

## MCP executor apply loop (planner-intent mode)

The planner emits a **selector-free intent** — the ONLY output since cutover
package 3:

```json
{
  "intent": {
    "intent_id": "<uuid>",
    "job_id": "<id-slug>",
    "job_url": "https://jobs.gupy.io/jobs/<id-slug>",
    "portal": "gupy",
    "profile": {"name": "...", "email": "...", "phone": "...", "resume_path": "...", "cover_text": "..."},
    "policy": {
      "require_confirmation_before_final_submit": true,
      "never_fill_credentials": true,
      "stop_on_auth_url": true,
      "max_steps": 25
    }
  },
  "dry_run": false
}
```

API-first mode adds a `metadata` block: `{job_title, job_company,
backend_job_id, api_base_url}` (bridged from `job_api.py`, issue #46). The
`--emit-intent` flag stays accepted for backward compatibility — intent
emission is now the default output.

The executor runs **observe → classify → act → verify** against each **live AX snapshot**
(`classify.py` is stdlib-only and pure):

1. **Observe** — take the current URL + AX snapshot (screenshot kept for the audit trail).
2. **Classify** — `classify.classify_page(url, ax_nodes)` → `{page, signals}` where page is
   one of `form | triagem | review | sucesso | auth | erro | start`. `auth` (auth URL or
   any credential field) is a hard policy stop; `erro` (validation alert or unrecognized
   page) means retry within the budget, never a blind fill and never a record.
3. **Act** — mapped by page kind: `start` → enter the flow; `form` → fill; `triagem` →
   select answers; `review` → confirm checkpoint (unless `--confirmed`/`--auto-apply`
   flipped the policy) then apply-final; `sucesso` → post-submit screenshot → verdict.
4. **Verify** — re-classify: `sucesso` evidence is collected by verdict.py; any other page
   counts as a stall against the `max_steps` budget → end `INCOMPLETE` and hand off.
5. **Record (strictly via verdict.py)** — an application is marked `applied` ONLY by
   `verdict.write_applied_record` after verified success evidence. The intent never writes.

### Fill provenance and verification (learned in production)

- Fill screening answers ONLY from declared values — intent `profile` or bot
  memory — and cite the source (memory section/key) when reporting. Values with
  no declared source are asked to the human (dictated), never invented.
- A fill counts ONLY when the next snapshot shows the values in the fields.
  Reporting a fill without re-snapshot evidence is a defect, not progress.
- React-controlled inputs ignore direct `.value` assignment: dispatch native
  `input`/`change` events (bubbles) and always re-read field values afterwards.

### Post-final-action evidence (learned in production)

- After the apply-final click, take a FRESH snapshot before evaluating: never
  reuse pre-submit evidence. The record must reflect the post-submit page.
- Records are written ONLY by calling `verdict.py` — never hand-compose the
  `applications/<job_id>.json` JSON.
- Known Gupy interstitial: the "Apresente-se!" modal (buttons "Personalizar
  candidatura" / "Finalizar candidatura") means NOT submitted — a pending
  "Finalizar" button is proof the flow is incomplete.
- Gupy may render success inline on the same URL (SPA, no `/success` change):
  the AX success text ("Candidatura finalizada!") is the primary signal,
  the URL segment only a bonus.

Policy gates: `never_fill_credentials` and `stop_on_auth_url` are always `true` and cannot
be disabled from the CLI; `require_confirmation_before_final_submit` flips to `false` via
`--confirmed` / `--auto-apply`.

---

## Browser recovery (issue #39)

Before emitting the intent (skipped on `--dry-run`), `apply.py` makes sure
the browser session is alive. The whole flow is stdlib-only (`urllib`,
`subprocess`, `time`, `os`) and never needs a login secret: Chromium is launched
with a persistent profile so its session survives restarts.

### `ensure_browser()` flow

```
check_cdp(cdp_url) ─ reachable ──────────────► status "ready"          (exit 0)
       │ down
       ▼
start_chromium(user_data_dir, port=9222)  ◄── launches `chromium --remote-debugging-port=9222 --user-data-dir=... --no-first-run`
       │ fails to start
       ▼
wait 3s ─────────────► re-check CDP
                              │ ok        ──► status "needs_login"     (exit 0 — human must log in)
                              │ still down ─► status "browser_unavailable" (exit 1 — real error)
```

| Status | Meaning | Exit code | JSON |
|---|---|---|---|
| `ready` | CDP was reachable on the first check | 0 | `{"status": "ready"}` |
| `needs_login` | CDP was down, Chromium started, CDP is up but the session is fresh — **the user must log in** | 0 (not an error) | `{"ok": true, "status": "needs_login", "detail": "Navegador indisponível, iniciando o Chromium. Faça login no Gupy e confirme."}` |
| `browser_unavailable` | Chromium failed to start, or CDP is still unreachable after the wait | 1 (real error) | `{"error": "browser_unavailable", "detail": "<reason>", "screenshot_path": "..."}` |

### What the bot must do

1. Run `apply.py` (without `--dry-run`). If the intent comes back, CDP was reachable — proceed normally.
2. If the output is `needs_login`, **stop and ask the human to log into Gupy in the launched Chromium window, then confirm**. Do **not** retry the intent until the user confirms the login.
3. If the output is `browser_unavailable`, report the failure and stop — do not keep retrying.

### `--check-browser` flag

Checks the browser state and exits without requiring `--job-url` / `--profile`
and without emitting an intent:

```
python3 skills/job-application/apply.py --check-browser [--cdp-url <url>] [--user-data-dir <dir>]
```

Output is a status JSON (`{"status": "ready" | "needs_login" | "browser_unavailable"}`)
with exit code 0 for `ready`/`needs_login` and 1 for `browser_unavailable`.

### Configuration

| Setting | Default | Override |
|---|---|---|
| CDP endpoint | `http://localhost:9222` | `--cdp-url` flag (also the endpoint the Playwright MCP server attaches to via `--cdp-endpoint`) |
| Chromium user data dir | `~/.chromium-profile-cdp` (expanduser) | `--user-data-dir` flag |
| Remote debugging port | `9222` | passed through to `start_chromium` |

> The persistent `user-data-dir` is what preserves the login session across
> restarts: after the first `needs_login` round-trip, the same profile is reused,
> so subsequent runs should skip the manual login step.

---

## Session expiry gate (issue #41)

Even with a running browser, a Gupy session can expire (or the app can redirect
to `/login`, `/auth`, `/signin` mid-flow). The bot must never fill a form on a
login page. `navigation.py` provides the pure, stateless detector:

```python
from navigation import verify_session

result = verify_session(current_url, login_hint_url=job_url)
# {"session": "active"}                          → proceed normally
# {"session": "expired", "detail": "Sessão expirada. Faça login no Gupy e digite confirmar.",
#  "login_url": "<job-url or current-url>"}       → stop, ask the human to log in
```

### Pre-flight gate (cutover pkg 3)

`apply.py` runs the check **before any intent is emitted** (skipped on
`--skip-session-check` and `--dry-run`). An expired session stops the planner
with a clean JSON error — exit 0 so the bot asks the human to authenticate and
then re-runs:

```
apply.py  ──► verify_session(current, job_url)
   ├─ session == "active"  ──► continue to idempotency + intent emission
   └─ {"session": "expired"}  ──► {"error": "session_expired",
                                    "detail": "Sessão expirada. Faça login no Gupy e digite confirmar.",
                                    "login_url": <job-url>}   (exit 0, no intent)
```

Mid-loop auth stops are handled at runtime by the executor: `stop_on_auth_url`
is a hard policy gate a login redirect trips, using the same `is_auth_url`
detector. `--skip-session-check` / `--dry-run` skip the planner-side check only.

---

## Confirmation protocol

- **The apply-final action is never taken without `--confirmed` or `--auto-apply`.**
  The intent carries `require_confirmation_before_final_submit: true` unless one
  of those flags is given — the executor MUST stop at the review page and wait
  for explicit user confirmation.
- After filling, the bot presents the captured form + screenshot + all values to
  the user before the final confirmation.
- `--auto-apply` is the **only** path that skips that confirmation — it implies
  explicit pre-authorization (policy flips to `false`) but never the safety gates.

---

## Recording & idempotency (#27)

Records live at:

```
<memory-dir>/applications/<job_id>.json
```

```json
{
  "job_id": "<id-slug>",
  "contact_email": "juan@example.com",
  "portal": "gupy",
  "applied_at": "<ISO timestamp>",
  "screenshot_path": "<memory-dir>/screenshots/<id-slug>.png",
  "status": "applied"
}
```

- The default `<memory-dir>` follows the bot memory convention `~/.hermes/profiles/jobhunter-bot/memails`.
- Before emitting an intent, apply.py checks `<memory-dir>/applications/<job_id>.json`; a record with `status: "applied"` short-circuits with `{"error": "already_applied"}` — the bot must never apply twice to the same job.
- **verdict.py is the SOLE writer** (cutover pkg 2): records are written only
  after verified success evidence (`verdict.write_applied_record` with
  `verdict.SUBMIT_OK`). `apply.py` never writes records and never calls the
  backend record API — the executor passes the outcome + evidence to the
  verdict stage (`--confirmed` / `--auto-apply` are verdict-input flags
  conveying that the run is authorized/committed).
- `--dry-run` never writes records.

### Quarantine (pinned path)

Non-verified attempts that need human review — `SUBMIT_DONE_NO_EVIDENCE`,
`INCOMPLETE` stalls, auth stops, errors — are quarantined at the **exact**
location (after enrichment/`classify.py` triage):

    <memory-dir>/attempts/quarantine/            # per-attempt JSONs
    <memory-dir>/attempts/quarantine/MANIFEST.md # human-readable index

With the default `<memory-dir>`, that is
`~/.hermes/profiles/jobhunter-bot/memails/attempts/quarantine/` +
`MANIFEST.md`. Attempt/applied records **never** live anywhere else — in
particular **never** at the profile root
(`~/.hermes/profiles/jobhunter-bot/`): the only record trees under `memails`
are `applications/`, `attempts/` and `screenshots/`. Violating this path
breaks the idempotency gate (#27) and the human-review flow.

---

## Error codes

| `error` code | Meaning | Bot behavior |
|---|---|---|
| `usage` | Missing/invalid CLI arguments | Fix invocation |
| `unknown_portal` | Portal not in the allow-list (v2: gupy + infojobs; `linkedin` NOT supported by design) | Do not plan |
| `invalid_profile` | Profile file missing / bad JSON / missing required fields (`name`, `email`, `phone`, `cv_path`, `cover_text`) | Show detail; fix profile |
| `refusal_draft_blocked` | Profile marks `no_apply` or cover text carries `NO_APPLY` (guardrail #28) | Stop — never send a refusal as an application |
| `invalid_job_url` | No `/jobs/<slug>` segment derivable | Show detail |
| `missing_api_token` | No Job Hunter API service token found (issue #46) — try `--api-token`, `JOBHUNTER_API_TOKEN`, or `<profile-dir>/api-token.txt` | Show the one-time service-token setup + save step (PT-BR) |
| `unauthorized` | Job Hunter API answered HTTP 401 (issue #47 — `X-Bot-Token` present but `bot.service.api-key` mismatch, or the feature is disabled) | Verify the bot secret equals `BOT_SERVICE_API_KEY` and `BOT_SERVICE_OWNER_USER_ID` is a positive id |
| `api_error` | Job Hunter API unreachable / unexpected response (issue #46) | Show detail; check `--api-base-url` and the backend |
| `no_jobs` | API list empty after fetch-if-empty (issue #46) | Nothing to apply to — stop |
| `already_applied` | Record exists with `status: applied` (guardrail #27) | Stop — duplicate apply refused |
| `session_expired` | Browser is on a login/auth page (guardrail #41, pre-flight) | Stop (exit 0) — ask the human to log in; `login_url` is the job link to return to |
| `browser_unavailable` | Chromium failed to start, or CDP still unreachable after recovery (issue #39) | Stop — real error; user cannot complete the application without a browser session. The *recovery* status `needs_login` is **not** an error (exit 0) |

Errors are always JSON: `{"error": <code>, "detail": <message>, "screenshot_path": <hint>}`. The `screenshot_path` hint tells the bot where to capture the current browser state on failure.

---

## Guardrails (from related issues — MUST be honored)

- **#27 idempotency** — never apply twice to the same job; memory record at `<memory-dir>/applications/<job_id>.json` gates re-applies.
- **#28 refusal** — never plan/send an application from a `NO_APPLY` draft; profile flag or `NO_APPLY` marker in cover text blocks planning.
- **#31 memory consultation** — records live under the bot memory convention so preferences/history are honoured before applying.
- **#38 auth/loop guard (retired for the apply loop)** — the deterministic planner guard was removed in cutover pkg 3; the executor loop re-checks the live page every step (`classify.py`): an auth page is a hard policy stop (`stop_on_auth_url`) and stalls burn the `max_steps` budget. `--visited-urls` / `--current-url` remain accepted flags.
- **#41 session expiry gate** — never fill a form on an expired session: the pre-flight gate stops with the `session_expired` error before any intent is emitted, and `stop_on_auth_url` covers mid-loop redirects.
- **#42 auto-apply / batch fill** — `--auto-apply` implies confirmation (policy flipped) and skips only the user confirmation of a healthy flow; it NEVER bypasses idempotency (#27), refusal (#28), or the session gate (#41). (The legacy `fill_form` batch step retired with the selector plan.)
- **Explicit confirmation** — no apply-final action without user confirmation (`require_confirmation_before_final_submit` is `true` unless `--confirmed` / `--auto-apply` flipped it); the bot never submits without that authorization.

---

## Non-goals

- **Email sending** — applications are form-based here; email delivery stays with the himalaya tool.
- **Login / sessions** — the bot never authenticates by itself: issue #39 recovery starts Chromium and hands a fresh session back as `needs_login` (the user logs in manually once), issue #41 detects an expired session as a pre-flight gate and stops, and a login redirect mid-loop hits the hard `stop_on_auth_url` policy rather than retrying.
- **CAPTCHA** — out of scope; if the portal presents one, the bot reports and stops.
- **Persistence of visited-URL history** — navigation history is passed per-invocation by the bot (`--visited-urls`); nothing stores state across calls or to disk.
- **Multi-step / paginated forms** — the loop handles flat Gupy-style forms; unknown multi-page flows stay with `job-portal-browser`.
- **Terminal / system operations** — out of scope for this skill.
- **LinkedIn automation** — deliberately unsupported. Applications on LinkedIn are done **manually** only; the LinkedIn Node.js scraper microservice is read-only and never submits. `linkedin` is absent from the portal allow-list (`--portal linkedin` → `unknown_portal`).
- **Other portals** — the allow-list errors cleanly (`unknown_portal`) instead of guessing.

---

## Limitations

- The planner never performs browser actions itself — it relies on the MCP executor loop deriving actions from live AX snapshots and executing them with the browser tool.
- Recording is evidence-gated: a record is written only after verified success evidence via `verdict.py`; a stalled/incomplete loop ends `INCOMPLETE` without a record.
- InfoJobs flows are **best-effort** under the shared loop — the markup can vary by region/over time, so the executor should confirm the `sucesso` page before any record is written.