# Skill: Job Application (Gupy + InfoJobs, structured)

> Hermes Agent skill for applying to job listings via a structured, planner-driven workflow.
> Designed for the `jobhunter-bot` profile. v2 supports Gupy + InfoJobs (issue #45).

---

## Purpose

Plan a job application as an ordered, machine-readable **action plan** that the bot executes through its browser tool. The plan is produced by `apply.py` (stdlib-only Python), which validates inputs, enforces apply guardrails, and emits steps (fill / upload / screenshot / confirm_checkpoint / submit) with CSS selectors loaded from `portals/<portal>.yaml`. Domain-specific portal helpers in `helpers/<portal>.py` (issue #45) build the per-portal flow (`click_apply_button` → `fill_form` → `handle_cover_letter` → always-gated `submit`).

This is the **structured counterpart** to the free-form `job-portal-browser` navigation skill:

| Situation | Skill |
|---|---|
| Application flow known in advance (Gupy form fields are stable, predictable) | `job-application` (this skill) — planner emits steps, bot executes |
| Unknown / free navigation, status checking, JS-only listing boards | `job-portal-browser` — bot drives freely |

---

## API-first flow (issue #46)

**The Job Hunter REST API is the PRIMARY job source.** Instead of receiving a
job URL from elsewhere, `apply.py` can pick the job straight from the backend:
top-scored from `GET /api/jobs` (`--from-api`) or by id from
`GET /api/jobs/{id}` (`--job-id <id>`, which prefills `jobUrl` +
`jobTitle`/`jobCompany` metadata into the plan).

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

# Plan from a specific job id (prefills job_url/title/company into the plan):
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
- The classic `--job-url` flow is unchanged when no API flag is supplied.
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
- The flow is unknown, multi-step/paginated, or needs login/session as the normal path (v2 assumes logged-in; if a login page appears mid-flow, the bot stops with `auth_required` and hands off to the human — it does **not** silently retry). Use `job-portal-browser` for unknown/free navigation.
- The portal is **not** Gupy or InfoJobs — `apply.py` errors cleanly (`unknown_portal`). **LinkedIn is explicitly OUT of scope for automation**: LinkedIn applications are done **manually** by the human only; the separate LinkedIn scraper microservice (Node.js) only *reads* listings and never submits applications. There is deliberately **no** `portals/linkedin.yaml` and **no** `helpers/linkedin.py` — requesting `--portal linkedin` errors cleanly.
- The paired draft is a `NO_APPLY` refusal — the bot must refuse to plan (guardrail #28).

---

## Inputs

| Parameter | Via flag | Type | Required | Description |
|---|---|---|---|---|
| `job-url` | `--job-url` | string | no * | Job URL (id derived from the portal's URL pattern). Required only when no API flag is used |
| API source | `--from-api` / `--job-id <id>` | flag / int | no | Issue #46: pick the top-scored job from the Job Hunter API, or a specific job detail (`GET /api/jobs/{id}` — prefills `jobUrl` + `jobTitle`/`jobCompany`) |
| API config | `--api-base-url` / `--api-token` / `--profile-dir` | string | no | Issue #46: backend base URL (default `http://localhost:8080`), token flag, and bot profile dir holding `api-token.txt` (default `~/.hermes/profiles/jobhunter-bot`) |
| API filters | `--min-score` / `--fetch-if-empty` | int / flag | no | Issue #46: `minScore` query filter; trigger a fetch (default gupy) when the list is empty — `--no-fetch-if-empty` disables it |
| profile | `--profile` | JSON file path | yes | `{name, email, phone, cv_path, cover_text}` (+ optional `no_apply: true`) |
| portal | `--portal` | string | no (default `gupy`) | `gupy` \| `infojobs` (issue #45; unknown values error cleanly — **`linkedin` is NOT supported, by design**) |
| memory dir | `--memory-dir` | dir path | no | Base for idempotency records; default `~/.hermes/profiles/jobhunter-bot/memails` |
| checkpoints | `--confirmed`, `--record-applied`, `--dry-run` | flags | no | See Confirmation protocol + Recording below |
| auto apply | `--auto-apply` | flag | no | Issue #42: implies `--confirmed` (submit emitted), omits the `confirm_checkpoint`, keeps `screenshot` + record. NEVER bypasses idempotency (#27), refusal (#28), or the session gate (#41) |
| auth/loop guard | `--current-url`, `--visited-urls` | string / comma-separated | no | Issues #38 + #41: current page + visited history so `apply.py` can detect auth/loop conditions and an expired session (see Step 4) |
| session expiry | `--skip-session-check` | flag | no | Issue #41: skip the session expiry gate (also skipped on `--dry-run`) |
| browser recovery | `--cdp-url`, `--user-data-dir` | URL / dir path | no | Issue #39: CDP endpoint (default `http://localhost:9222`, also from `portals/gupy.yaml` `cdp_url` key) and persistent Chromium profile dir (default `~/.chromium-profile-cdp`) |
| status check | `--check-browser` | flag | no | Issue #39: print browser status as JSON and exit — no action plan required |
| verification | `--verify-with` | string | no (default `screenshot`) | Issue #43: `screenshot` (default) or `ax` — how the executor verifies `fill_form`/`submit` steps. Invalid modes error cleanly |

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

1. **Plan** — the bot runs `apply.py` with the job URL, profile, and portal:
   ```
   python3 skills/job-application/apply.py \
     --job-url "https://jobs.gupy.io/jobs/<id-slug>" \
     --profile profile.json --portal gupy --memory-dir <memails-dir>
   ```
2. **Validate** — apply.py checks portal YAML, required profile fields, and the guardrails (#28 refusal, #27 idempotency). Any failure returns a JSON error.
3. **Receive the action plan** — a JSON list of steps with CSS selectors (schema below).
4. **Execute step by step** — the bot opens the form URL and executes each step (below). The plan begins with a `verify_session` step (issue #41): before any fill, navigate to the step's `url` and call `verify_session(current_url, login_hint_url=<job-url>)`.

   **The `fill_form` batch step (issue #42) — ONE browser call:**
   - All non-submit fields are consolidated into a single `{"type": "fill_form", "action": "fill_form", "fields": [...]}` step preserving portal order (`name`, `email`, `phone`, `cv_upload`, `cover_letter`).
   - The bot fills/attaches every field in `fields` in a **single browser call** (no page reload between fields). Each entry is `{name, selector, type, value}` where `type` is `fill` (text) or `upload` (file attachment).
   - Do NOT split `fill_form` into per-field steps.

   **Session expiry gate (issue #41):**
   - **Verify the session first** — every plan starts with `{"type": "verify_session", "expect": "not_auth_page", "on_expired": "ask_login_and_confirm"}`. The bot executes it by checking `is_auth_url(current_url)` on the job page.
   - **Stop on expiry** — if the result is `{"session": "expired"}` the bot must **not** fill anything: the plan only contains the `verify_session` step + a `confirm_checkpoint` whose note is `Sessão expirada. Faça login no Gupy e digite confirmar.` and whose `login_url` is the job link. Ask the human to log in, then re-run.
   - **`--skip-session-check`** — removes the `verify_session` step (also removed on `--dry-run`); used when the bot already knows the session is alive.

   **Before every navigation, also run the auth/loop guard (`navigation.py`, issue #38):**
   - **Check auth first** — call `is_auth_url(current_url)`. If the current URL's path contains `/login`, `/auth`, `/signin`, or `/candidates/auth` (case-insensitive), the bot is being redirected to a login page and **must stop immediately** (`auth_required`).
   - **Track visited URLs** — keep a list of every page visited during this application and pass it as `--visited-urls` (comma-separated) with the current page as `--current-url` on each re-invocation of `apply.py`.
   - **Abort after 3 visits** — if the same (normalized) URL has been visited 3+ times, the bot is stuck in a navigation loop an **must abort** (`navigation_loop`).
   - **Ask the human** — on either stop, output the JSON error (which carries `manual_url` — the direct link to the job where the user can finish by hand — and `screenshot_path` for debugging) and hand off to the user.

   Both guards keep the planner **stateless**: visited history and the current URL are supplied by the bot as arguments each call; `navigation.py` never stores state itself.

5. **Screenshot + pause (interactive mode)** — at `screenshot`, the bot captures the filled form to the given path; at `confirm_checkpoint`, the bot stops and asks the user to confirm every value. In `--auto-apply` mode the `confirm_checkpoint` is omitted — the user pre-authorized the run.
6. **Submit (only after confirmation)** — the bot re-runs with `--confirmed` (or `--auto-apply`) to obtain the `submit` step, OR the user confirms the checkpoint and the bot proceeds with the confirmed plan; the `submit` step is executed last.
7. **Record** — after the browser submit, the bot calls `apply.py --record-applied` (or the confirmed/`--auto-apply` run already recorded it) so future runs short-circuit with `already_applied`.

---

## Portal helpers (issue #45)

Each portal maps to a YAML config (`portals/<portal>.yaml`) **and** a Python helper module (`helpers/<portal>.py`). `apply.py` loads both by name (`--portal`); either missing on disk means `unknown_portal`.

### Layout

```
skills/job-application/
├── portals/
│   ├── gupy.yaml          # Gupy selector mapping (input[name='name'], ...)
│   └── infojobs.yaml      # InfoJobs selector mapping (BEST-EFFORT)
└── helpers/
    ├── __init__.py        # shared non_submit_fields(); must NOT import portal modules
    ├── gupy.py            # Gupy flow builder (same interface as infojobs.py)
    └── infojobs.py        # InfoJobs flow builder
```

### Per-portal interface (identical for both portals)

All helper functions are **pure** (return action dicts; no browser/network I/O) and take the portal mapping as `portal_cfg`:

| Function | Returns | Notes |
|---|---|---|
| `click_apply_button(portal_cfg, url)` | `{"type": "click", "action": "click_apply_button", "selector": <apply_button_selector>, "url": <job url>}` | Selector from `portal_cfg["apply_button_selector"]` or a portal best-effort default |
| `fill_form(profile, portal_cfg)` | `{"type": "fill_form", "action": "fill_form", "fields": [{name, selector, type, value}...]}` | All non-submit fields in portal order; same batch shape as the plan's `fill_form` step (issue #42) |
| `handle_cover_letter(profile, portal_cfg)` | `{"type": "fill", "action": "handle_cover_letter", "field": "cover_letter", "selector": <...>, "value": profile["cover_text"]}` | |
| `submit(portal_cfg, confirmed=False)` | `{"type": "submit", "action": "submit", "selector": <submit selector>}` | **Raises ValueError unless `confirmed=True`** — never called without explicit confirmation |
| `apply(job_url, profile, portal_cfg, confirmed=False)` | `[click_apply_button, fill_form, handle_cover_letter, submit]` | Orchestration; a `confirmed=False` run raises via `submit` |

Signature parity between `gupy.py` and `infojobs.py` is enforced by `helpers_test.py`. The `helpers` package imports lazily: `import helpers` never pulls in a portal module.

> **LinkedIn is deliberately absent** (no `helpers/linkedin.py`, no `portals/linkedin.yaml`). Applications on LinkedIn are manual-only; the LinkedIn scraper microservice is read-only. `--portal linkedin` → `unknown_portal`.

---

## Verification hierarchy (issue #43)

After a `fill_form` / `submit` step, the executor verifies the page state instead of relying on an LLM-vision screenshot by default. `apply.py` emits a `"verification"` object on each `fill_form` and `submit` step telling the executor how (and, for AX, what) to check:

```json
{"verification": {"method": "screenshot", "ax_query": null}}
{"verification": {"method": "ax", "ax_query": {"role": "button", "name": "Enviar"}}}
```

- **`--verify-with screenshot` (default)** — behavior unchanged: the executor captures a screenshot. `ax_query` is `null`.
- **`--verify-with ax`** — the executor verifies via the **accessibility tree** (fast, no LLM vision cost). The `ax_query` is a best-effort hint interpreted against the tree:
  - `fill_form` → `{"role": "textbox"}` (at least one editable field present)
  - `submit` → `{"role": "button", "name": "Enviar"}` (confirm control present before submitting)

### `ax_tree.py` (new module, issue #43)

`ax_tree.py` (stdlib: `urllib`, `json`, `typing` only — separate from `navigation.py`, which stays stateless by design and never touches the network) provides:

| Function | Purpose |
|---|---|
| `fetch_ax_tree(cdp_url, timeout=5, cdp_post=None)` | Fetch + normalize the AX tree via an **injected** `cdp_post(method, params)` transport. No transport → `{"error": "cdp_transport_required"}`; transport failure → `{"error": "ax_fetch_failed", "detail": ...}`; success → `{"nodes": [...]}` |
| `snapshot_from_cdp_response(payload)` | Pure parser: flatten a CDP `Accessibility.getFullAXTree` payload into `[{role, name, value, backendNodeId, ignored}]`, **skipping ignored nodes** |
| `find_in_ax_tree(nodes, query)` | Pure search: case-insensitive substring over `role`+`name`+`value`. `query` is a string or a dict like `{"role": "button", "name": "Enviar"}`. Empty/no-match → `[]` |

Because stdlib has no WebSocket client, this module does **not** own a CDP session: the bot (which has a real CDP/WebSocket transport) injects `cdp_post`, and tests inject fakes. The exposed `getFullAXTree` is the CDP HTTP-readable surface stub; in practice the transport is bot-side.

### Hierarchy

1. **AX tree first (primary)** — fast, no LLM vision cost, deterministic. Preferred for routine `fill_form` / `submit` verification.
2. **Screenshot (fallback only)** — used for **errors, CAPTCHA, unexpected layout, and the final user confirmation**, where a human/LLM must visually confirm. Screenshot *steps* (the audit-trail capture before `confirm_checkpoint`, issue #42) are unchanged and independent of the verification method.

> **Note:** the "50%+ faster" acceptance metric is measured on the bot host (manual benchmarking), not provable by unit tests. The unit suite here proves the AX parsing/searching and the plan metadata wiring only.

---

## Browser recovery (issue #39)

Before generating the action plan (skipped on `--dry-run`), `apply.py` makes sure
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

1. Run `apply.py` (without `--dry-run`). If the plan comes back, CDP was reachable — proceed normally.
2. If the output is `needs_login`, **stop and ask the human to log into Gupy in the launched Chromium window, then confirm**. Do **not** retry the action plan until the user confirms the login.
3. If the output is `browser_unavailable`, report the failure and stop — do not keep retrying.

### `--check-browser` flag

Checks the browser state and exits without requiring `--job-url` / `--profile`
and without emitting an action plan:

```
python3 skills/job-application/apply.py --check-browser [--cdp-url <url>] [--user-data-dir <dir>]
```

Output is a status JSON (`{"status": "ready" | "needs_login" | "browser_unavailable"}`)
with exit code 0 for `ready`/`needs_login` and 1 for `browser_unavailable`.

### Configuration

| Setting | Default | Override |
|---|---|---|
| CDP endpoint | `http://localhost:9222` | `--cdp-url` flag, or `cdp_url:` key in `portals/gupy.yaml` |
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

### Verify-then-fill flow

```
apply.py  ──► plan[0] = verify_session  (expect "not_auth_page")
   │
   ├─ verify_session(current, job_url)["session"] == "active"  ──► continue with fill/upload steps
   └─ session == "expired"  ──► plan stops at confirm_checkpoint:
                                note  = "Sessão expirada. Faça login no Gupy e digite confirmar."
                                login_url = <job-url>
                                (no fill / upload / submit steps at all — exit 0)
```

### Integration with the other guards

- **Order in `apply.py`:** browser recovery (#39) → **session expiry gate (#41)** → auth/loop guard (#38) → idempotency (#27) → action plan.
- An expired session supersedes the old `auth_required` error for the default flow: a login page now yields the expired plan above (exit 0) instead of an error. The #38 `auth_required` error still fires when the session check is disabled (`--skip-session-check`).
- Both `--skip-session-check` and `--dry-run` skip the gate (no `verify_session` step, `"sessionCheck": false` in the plan).
- The `login_url` hint is the job URL (where the human returns after authenticating); when no hint is given it falls back to the current URL.

---

## Action-plan JSON schema

```json
{
  "ok": true,
  "portal": "gupy",
  "jobId": "<id-slug>",
  "jobUrl": "https://jobs.gupy.io/jobs/<id-slug>",
  "form_url": "https://jobs.gupy.io/jobs/<id-slug>",
  "confirmed": false,
  "confirmationRequired": true,
  "dryRun": false,
  "sessionCheck": true,
  "sessionExpired": false,
  "autoApply": false,
  "steps": [
    {"step": 1, "type": "verify_session", "action": "verify_session", "url": "https://jobs.gupy.io/jobs/<id-slug>", "expect": "not_auth_page", "on_expired": "ask_login_and_confirm"},
    {"step": 2, "type": "fill_form", "action": "fill_form", "fields": [
        {"name": "name",          "selector": "input[name='name']",          "type": "fill",   "value": "Juan Antonio Peruzzo"},
        {"name": "email",         "selector": "input[name='email']",         "type": "fill",   "value": "juan@example.com"},
        {"name": "phone",         "selector": "input[name='phone']",         "type": "fill",   "value": "+55 42 99833-1363"},
        {"name": "cv_upload",     "selector": "input[type='file']",          "type": "upload", "value": "/home/juan/cv.pdf"},
        {"name": "cover_letter",  "selector": "textarea[name='coverLetter']", "type": "fill",   "value": "Olá! ..."}
    ],
     "verification": {"method": "screenshot", "ax_query": null}},   <!-- issue #43: {"method": "ax", "ax_query": {"role": "textbox"}} with --verify-with ax -->
    {"step": 3, "type": "screenshot", "path": "<memory-dir>/screenshots/<id-slug>.png"},
    {"step": 4, "type": "confirm_checkpoint", "screenshot_path": "<...png>",
     "note": "PAUSE - do not continue until the user confirms every value and the screenshot"},
    {"step": 5, "type": "submit", "selector": "button[type='submit']",
     "verification": {"method": "screenshot", "ax_query": null}}   <!-- issue #43 -->
  ]
}
```

Step types: `verify_session` (issue #41, always first unless `--skip-session-check`), `fill_form` (issue #42 — single batch step for all non-submit fields; executor contract: **one browser call**), `screenshot`, `confirm_checkpoint`, `submit`. `fill_form` and `submit` carry a `"verification"` object (issue #43) controlling how the executor verifies the step — see "Verification hierarchy".

### `--auto-apply` plan (issue #42)

`apply.py --auto-apply` produces a plan where:
- `submit` is emitted (same gate as `--confirmed`; `"confirmed": true`, `"confirmationRequired": false`),
- the `confirm_checkpoint` step is **omitted** (the user pre-authorized the run),
- `screenshot` and the applied record (`plan["recorded"]`) are kept for the audit trail,
- `"autoApply": true` is set on the plan.

**Safety guarantees (non-negotiable, always evaluated before plan emission):**
- **#27 idempotency** — an existing `already_applied` record still short-circuits with `{"error": "already_applied"}`.
- **#28 refusal** — a `NO_APPLY` / refusal profile still blocks with `{"error": "refusal_draft_blocked"}`.
- **#41 session gate** — an expired session still yields the short expired plan (verify + login checkpoint, no fill/submit). Auto-apply skips the *user confirmation of a healthy flow*, never the safety checks.

```json
{
  "ok": true, "portal": "gupy", "jobId": "<id-slug>", "jobUrl": "<...>",
  "confirmed": true, "confirmationRequired": false, "dryRun": false,
  "sessionCheck": true, "sessionExpired": false, "autoApply": true,
  "steps": [
    {"step": 1, "type": "verify_session", "action": "verify_session", "url": "<job-url>", "expect": "not_auth_page", "on_expired": "ask_login_and_confirm"},
    {"step": 2, "type": "fill_form", "action": "fill_form", "fields": [{"name": "name", "selector": "input[name='name']", "type": "fill", "value": "..."}, ...], "verification": {"method": "screenshot", "ax_query": null}},
    {"step": 3, "type": "screenshot", "path": "<memory-dir>/screenshots/<id-slug>.png"},
    {"step": 4, "type": "submit", "selector": "button[type='submit']", "verification": {"method": "screenshot", "ax_query": null}}
  ]
}
```

### Expired-session plan (issue #41)

When `verify_session` detects an expired session, `apply.py` returns exit 0 with a **short plan** — the bot must stop and ask the human to log in (never fill/submit):

```json
{
  "ok": true,
  "portal": "gupy",
  "jobId": "<id-slug>",
  "jobUrl": "https://jobs.gupy.io/jobs/<id-slug>",
  "confirmed": false,
  "confirmationRequired": true,
  "dryRun": false,
  "sessionCheck": true,
  "sessionExpired": true,
  "autoApply": false,
  "steps": [
    {"step": 1, "type": "verify_session", "action": "verify_session", "url": "https://jobs.gupy.io/jobs/<id-slug>", "expect": "not_auth_page", "on_expired": "ask_login_and_confirm"},
    {"step": 2, "type": "confirm_checkpoint",
     "note": "Sessão expirada. Faça login no Gupy e digite confirmar.",
     "login_url": "https://jobs.gupy.io/jobs/<id-slug>",
     "screenshot_path": "<memory-dir>/screenshots/<id-slug>.png"}
  ]
}
```

---

## Confirmation protocol

- **Submit is never emitted without `--confirmed` or `--auto-apply`.** An unconfirmed plan ends at `confirm_checkpoint` and sets `"confirmationRequired": true` — the bot MUST stop there and wait for explicit user confirmation.
- All form fields are filled in a single `fill_form` batch call; after the screenshot the bot presents the captured form + all values to the user.
- The bot **never** clicks submit without that explicit confirmation.
- `--auto-apply` is the **only** path that skips the `confirm_checkpoint` — it implies explicit pre-authorization and still keeps the screenshot + record. Interactive (`--confirmed`) plans keep the checkpoint.

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
- Before planning, apply.py checks `<memory-dir>/applications/<job_id>.json`; a record with `status: "applied"` short-circuits with `{"error": "already_applied"}` — the bot must never apply twice to the same job.
- Recording triggers: `--confirmed`, `--auto-apply` (planner treats authorization as the commit point) or `--record-applied` (bot calls it **post-submit** after the real browser step succeeded). `--dry-run` never writes records.

### Record-back (backend canonical record)

The **local file is a fast pre-check**; the **backend is the canonical record**.
After a confirmed local record is written (`--record-applied` or the
`--auto-apply` submit path), apply.py ALSO posts an applied marker upstream:

```
POST <api-base-url>/api/jobs/<id>/applied      # empty JSON {} + X-Bot-Token
```

- Only for **API-sourced jobs** (`--job-id` / `--from-api`) — the numeric
  backend id is known; the classic `--job-url` flow (slug only) never posts back.
- Triggered only when the backend is reachable: `--api-base-url` set and a
  token resolvable (`--api-token` / `JOBHUNTER_API_TOKEN` /
  `<profile-dir>/api-token.txt`). Otherwise no `backend_record` field is emitted.
- **Failure semantics:** the backend is best-effort. On success the echoed
  `{jobId, status}` is attached as `"backend_record": {"ok": true, ...}`. On
  failure (unreachable host, 401, 404, HTTP error) apply.py warns with
  `"backend_record": {"ok": false, "error": <code>}` and **never** fails the
  local record nor changes the exit code.
- **Never sent** on `--dry-run`, an unconfirmed plan, an expired session, or a
  refusal path — the same guards that block recording locally also prevent the
  backend POST.
- The backend endpoint is idempotent: re-posting the marker returns the
  existing applied record instead of failing.

---

## Error codes

| `error` code | Meaning | Bot behavior |
|---|---|---|
| `usage` | Missing/invalid CLI arguments | Fix invocation |
| `unknown_portal` | Portal YAML/helper absent (v2: gupy + infojobs; `linkedin` NOT supported by design) | Do not plan; no YAML loaded |
| `invalid_profile` | Profile file missing / bad JSON / missing required fields | Show detail; fix profile |
| `refusal_draft_blocked` | Profile marks `no_apply` or cover text carries `NO_APPLY` (guardrail #28) | Stop — never send a refusal as an application |
| `invalid_job_url` | No `/jobs/<slug>` segment derivable | Show detail |
| `missing_api_token` | No Job Hunter API service token found (issue #46) — try `--api-token`, `JOBHUNTER_API_TOKEN`, or `<profile-dir>/api-token.txt` | Show the one-time service-token setup + save step (PT-BR) |
| `unauthorized` | Job Hunter API answered HTTP 401 (issue #47 — `X-Bot-Token` present but `bot.service.api-key` mismatch, or the feature is disabled) | Verify the bot secret equals `BOT_SERVICE_API_KEY` and `BOT_SERVICE_OWNER_USER_ID` is a positive id |
| `api_error` | Job Hunter API unreachable / unexpected response (issue #46) | Show detail; check `--api-base-url` and the backend |
| `no_jobs` | API list empty after fetch-if-empty (issue #46) | Nothing to apply to — stop |
| `already_applied` | Record exists with `status: applied` (guardrail #27) | Stop — duplicate apply refused |
| `auth_required` | Current URL is a login/auth/signin page while the session check is disabled (issue #38, reached with `--skip-session-check`) | Stop immediately — ask the human; report `manual_url` + `screenshot_path` |
| `navigation_loop` | Same URL visited 3+ times (issue #38) | Stop — ask the human; report `manual_url` + `screenshot_path` |
| `browser_unavailable` | Chromium failed to start, or CDP still unreachable after recovery (issue #39) | Stop — real error; user cannot complete the application without a browser session. The *recovery* status `needs_login` is **not** an error (exit 0) |

Errors are always JSON: `{"error": <code>, "detail": <message>, "screenshot_path": <hint>}`. The `screenshot_path` hint tells the bot where to capture the current browser state on failure. Auth/loop errors additionally carry `manual_url` — the direct job link where the user can complete the application by hand.

---

## Guardrails (from related issues — MUST be honored)

- **#27 idempotency** — never apply twice to the same job; memory record at `<memory-dir>/applications/<job_id>.json` gates re-applies.
- **#28 refusal** — never plan/send an application from a `NO_APPLY` draft; profile flag or `NO_APPLY` marker in cover text blocks planning.
- **#31 memory consultation** — records live under the bot memory convention so preferences/history are honoured before applying.
- **#38 auth/loop guard** — never retry a login/auth redirect or a navigation loop; check `is_auth_url` before every navigation and abort after 3 visits, always handing a `manual_url` + `screenshot_path` back to the human.
- **#41 session expiry gate** — never fill a form on an expired session: the plan always opens with `verify_session`; an expired session stops at the login `confirm_checkpoint` (PT-BR note + `login_url`) with no fill/upload/submit steps. Supersedes #38 for the default flow.
- **#42 auto-apply / batch fill** — `--auto-apply` implies confirmation + skips the manual `confirm_checkpoint` but NEVER bypasses safety: idempotency (#27), refusal (#28), and the session gate (#41) still gate the plan. All form fields ship as one `fill_form` batch step (single executor browser call).
- **Explicit confirmation** — no `submit` step without `--confirmed`; the bot never submits without user confirmation. `--auto-apply` (issue #42) IS that explicit pre-authorization — it implies `--confirmed` and skips only the manual `confirm_checkpoint`, never the safety gates.

---

## Non-goals

- **Email sending** — applications are form-based here; email delivery stays with the himalaya tool.
- **Login / sessions** — the bot never authenticates by itself: issue #39 recovery starts Chromium and hands a fresh session back as `needs_login` (the user logs in manually once), issue #41 detects an expired session before any fill and stops at a login `confirm_checkpoint`, and a login redirect mid-flow stops cleanly rather than retrying.
- **CAPTCHA** — out of scope; if the portal presents one, the bot reports and stops.
- **Persistence of visited-URL history** — navigation history is passed per-invocation by the bot (`--visited-urls`); the guard never stores state across calls or to disk.
- **Multi-step / paginated forms** — v2 is a single flat form per portal (fields defined in `portals/gupy.yaml` / `portals/infojobs.yaml`).
- **Terminal / system operations** — out of scope for this skill.
- **LinkedIn automation** — deliberately unsupported. Applications on LinkedIn are done **manually** only; the LinkedIn Node.js scraper microservice is read-only and never submits. No `linkedin` YAML/helper exists (`--portal linkedin` → `unknown_portal`).
- **Other portals** — the YAML/helper loader errors cleanly (`unknown_portal`) instead of guessing.

---

## Limitations

- Selectors are static in the per-portal YAML files; if a portal changes its markup, the YAML (not the bot) must be updated. **InfoJobs selectors are best-effort** and should be verified before heavy use — the markup can vary by region/over time.
- The planner never performs browser actions itself — it relies on the bot's browser tool executing the plan.
- Recording is best-effort: a confirmed/recorded run assumes the submit step actually succeeded in the browser; the bot should prefer `--record-applied` after confirming completion.