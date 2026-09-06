# Skill: Job Application (Gupy, structured)

> Hermes Agent skill for applying to Gupy job listings via a structured, planner-driven workflow.
> Designed for the `jobhunter-bot` profile. v1 supports Gupy only.

---

## Purpose

Plan a Gupy job application as an ordered, machine-readable **action plan** that the bot executes through its browser tool. The plan is produced by `apply.py` (stdlib-only Python), which validates inputs, enforces apply guardrails, and emits steps (fill / upload / screenshot / confirm_checkpoint / submit) with CSS selectors loaded from `portals/<portal>.yaml`.

This is the **structured counterpart** to the free-form `job-portal-browser` navigation skill:

| Situation | Skill |
|---|---|
| Application flow known in advance (Gupy form fields are stable, predictable) | `job-application` (this skill) — planner emits steps, bot executes |
| Unknown / free navigation, status checking, JS-only listing boards | `job-portal-browser` — bot drives freely |

---

## When to use

- The portal is **Gupy** and the job URL follows `https://<portal>.gupy.io/jobs/<id-slug>`.
- The bot has structured application data (name, email, phone, resume path, cover text) from the app profile.
- The user explicitly asked the bot to apply, and the confirmation protocol below can be honored.

### When NOT to use

- The application is sent **by email** — use the himalaya email tool / `company-scraper` skill instead.
- The flow is unknown, multi-step/paginated, or needs login/session as the normal path (v1 assumes logged-in; if a login page appears mid-flow, the bot stops with `auth_required` and hands off to the human — it does **not** silently retry). Use `job-portal-browser` for unknown/free navigation.
- The portal is **not** Gupy — `apply.py` errors cleanly (`unknown_portal`).
- The paired draft is a `NO_APPLY` refusal — the bot must refuse to plan (guardrail #28).

---

## Inputs

| Parameter | Via flag | Type | Required | Description |
|---|---|---|---|---|
| `job-url` | `--job-url` | string | yes | Gupy job URL (id derived from the `/jobs/<slug>` segment) |
| profile | `--profile` | JSON file path | yes | `{name, email, phone, cv_path, cover_text}` (+ optional `no_apply: true`) |
| portal | `--portal` | string | yes | `gupy` (v1 only; unknown values error cleanly) |
| memory dir | `--memory-dir` | dir path | no | Base for idempotency records; default `~/.hermes/profiles/jobhunter-bot/memails` |
| checkpoints | `--confirmed`, `--record-applied`, `--dry-run` | flags | no | See Confirmation protocol + Recording below |
| auth/loop guard | `--current-url`, `--visited-urls` | string / comma-separated | no | Issue #38: current page + visited history so `apply.py` can detect auth/loop conditions (see Step 4) |

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
4. **Execute step by step** — the bot opens the form URL and performs each `fill` / `upload` step on the given selector, one at a time.

   **Before every navigation, run the auth/loop guard (`navigation.py`, issue #38):**
   - **Check auth first** — call `is_auth_url(current_url)`. If the current URL's path contains `/login`, `/auth`, `/signin`, or `/candidates/auth` (case-insensitive), the bot is being redirected to a login page and **must stop immediately** (`auth_required`).
   - **Track visited URLs** — keep a list of every page visited during this application and pass it as `--visited-urls` (comma-separated) with the current page as `--current-url` on each re-invocation of `apply.py`.
   - **Abort after 3 visits** — if the same (normalized) URL has been visited 3+ times, the bot is stuck in a navigation loop an **must abort** (`navigation_loop`).
   - **Ask the human** — on either stop, output the JSON error (which carries `manual_url` — the direct link to the job where the user can finish by hand — and `screenshot_path` for debugging) and hand off to the user.

   Both guards keep the planner **stateless**: visited history and the current URL are supplied by the bot as arguments each call; `navigation.py` never stores state itself.

5. **Screenshot + pause** — at `screenshot`, the bot captures the filled form to the given path; at `confirm_checkpoint`, the bot stops and asks the user to confirm every value.
6. **Submit (only after confirmation)** — the bot re-runs with `--confirmed` to obtain the `submit` step, OR the user confirms the checkpoint and the bot proceeds with the confirmed plan; the `submit` step is executed last.
7. **Record** — after the browser submit, the bot calls `apply.py --record-applied` (or the confirmed run already recorded it) so future runs short-circuit with `already_applied`.

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
  "steps": [
    {"step": 1, "type": "fill",   "field": "name",         "selector": "input[name='name']", "value": "Juan Antonio Peruzzo"},
    {"step": 2, "type": "fill",   "field": "email",        "selector": "input[name='email']", "value": "juan@example.com"},
    {"step": 3, "type": "fill",   "field": "phone",        "selector": "input[name='phone']", "value": "+55 42 99833-1363"},
    {"step": 4, "type": "upload", "field": "cv_upload",    "selector": "input[type='file']", "value": "/home/juan/cv.pdf"},
    {"step": 5, "type": "fill",   "field": "cover_letter", "selector": "textarea[name='coverLetter']", "value": "Olá! ..."},
    {"step": 6, "type": "screenshot", "path": "<memory-dir>/screenshots/<id-slug>.png"},
    {"step": 7, "type": "confirm_checkpoint", "screenshot_path": "<...png>",
     "note": "PAUSE - do not continue until the user confirms every value and the screenshot"},
    {"step": 8, "type": "submit", "selector": "button[type='submit']"}
  ]
}
```

Step types: `fill`, `upload`, `screenshot`, `confirm_checkpoint`, `submit`.

---

## Confirmation protocol

- **Submit is never emitted without `--confirmed`.** An unconfirmed plan ends at `confirm_checkpoint` and sets `"confirmationRequired": true` — the bot MUST stop there and wait for explicit user confirmation.
- Each form field is filled one at a time; after the screenshot the bot presents the captured form + all values to the user.
- The bot **never** clicks submit without that explicit confirmations.

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
- Recording triggers: `--confirmed` (planner treats authorization as the commit point) or `--record-applied` (bot calls it **post-submit** after the real browser step succeeded). `--dry-run` never writes records.

---

## Error codes

| `error` code | Meaning | Bot behavior |
|---|---|---|
| `usage` | Missing/invalid CLI arguments | Fix invocation |
| `unknown_portal` | Portal YAML absent (v1: gupy only) | Do not plan; no YAML loaded |
| `invalid_profile` | Profile file missing / bad JSON / missing required fields | Show detail; fix profile |
| `refusal_draft_blocked` | Profile marks `no_apply` or cover text carries `NO_APPLY` (guardrail #28) | Stop — never send a refusal as an application |
| `invalid_job_url` | No `/jobs/<slug>` segment derivable | Show detail |
| `already_applied` | Record exists with `status: applied` (guardrail #27) | Stop — duplicate apply refused |
| `auth_required` | Current URL is a login/auth/signin page (issue #38) | Stop immediately — ask the human; report `manual_url` + `screenshot_path` |
| `navigation_loop` | Same URL visited 3+ times (issue #38) | Stop — ask the human; report `manual_url` + `screenshot_path` |

Errors are always JSON: `{"error": <code>, "detail": <message>, "screenshot_path": <hint>}`. The `screenshot_path` hint tells the bot where to capture the current browser state on failure. Auth/loop errors additionally carry `manual_url` — the direct job link where the user can complete the application by hand.

---

## Guardrails (from related issues — MUST be honored)

- **#27 idempotency** — never apply twice to the same job; memory record at `<memory-dir>/applications/<job_id>.json` gates re-applies.
- **#28 refusal** — never plan/send an application from a `NO_APPLY` draft; profile flag or `NO_APPLY` marker in cover text blocks planning.
- **#31 memory consultation** — records live under the bot memory convention so preferences/history are honoured before applying.
- **#38 auth/loop guard** — never retry a login/auth redirect or a navigation loop; check `is_auth_url` before every navigation and abort after 3 visits, always handing a `manual_url` + `screenshot_path` back to the human.
- **Explicit confirmation** — no `submit` step without `--confirmed`; the bot never submits without user confirmation.

---

## Non-goals

- **Email sending** — applications are form-based here; email delivery stays with the himalaya tool.
- **Login / sessions** — v1 assumes the bot is already logged into Gupy; a login/auth redirect mid-flow stops cleanly (`auth_required`) and hands the job back to the human rather than trying to authenticate.
- **CAPTCHA** — out of scope; if the portal presents one, the bot reports and stops.
- **Persistence of visited-URL history** — navigation history is passed per-invocation by the bot (`--visited-urls`); the guard never stores state across calls or to disk.
- **Multi-step / paginated forms** — v1 is a single flat form (fields defined in `portals/gupy.yaml`).
- **Terminal / system operations** — out of scope for this skill.
- **Other portals** — the YAML loader errors cleanly (`unknown_portal`) instead of guessing.

---

## Limitations

- Selectors are static in `portals/gupy.yaml`; if Gupy changes its markup, the YAML (not the bot) must be updated.
- The planner never performs browser actions itself — it relies on the bot's browser tool executing the plan.
- Recording is best-effort: a confirmed/recorded run assumes the submit step actually succeeded in the browser; the bot should prefer `--record-applied` after confirming completion.