# Skill: Job Portal Browser

> Hermes Agent skill for browser automation on job portals that lack a public API.
> Designed for the `jobhunter-bot` profile to handle cases the primary scrapers cannot.

---

## Purpose

Drive a headless browser (Playwright-style automation) to complement the primary scrapers (Gupy, InfoJobs, LinkedIn). The browser skill is a **fallback and complement**, never a replacement for the existing pipeline.

It covers three distinct situations the API/HTML scrapers cannot handle:

1. **Application-form filling** — portals where applications are submitted through interactive web forms rather than email.
2. **Status checking** — re-visiting a portal where a previous application was made to observe its state.
3. **Dynamic-site scraping** — listing boards that render content only via JavaScript, exposing no predictable JSON endpoint and returning empty/no-op content to static fetches.

---

## API-first priority (issue #46) — query the API BEFORE scraping

**The Job Hunter REST API is the first-line job source.** The browser skill is a
true fallback: it is used **only when the API has no data** for the task.

### Standing rule

> **Always query the Job Hunter API first (`GET /api/jobs`, `GET /api/jobs/{id}`).
> Only when the API returns no listing (or no matching listing) fall back to
> scraping the portal with the browser.**

The API answers every request with real, persisted jobs from the primary
scrapers (Gupy / InfoJobs / LinkedIn microservice) — so browsing the portal
again to *discover* listings is redundant work.

### `job_api` integration

The bot profile carries a companion module: `skills/job-application/job_api.py`
(stdlib-only, `X-Bot-Token` service auth — issue #47). Its functions mirror
this skill's needs:

| Need | Call |
|---|---|
| List jobs (`hasEmail` filter on by default) | `job_api.api_list_jobs(base_url, token, min_score=..., has_email=True)` |
| One job detail | `job_api.api_get_job(base_url, token, job_id)` |
| Trigger a scraping cycle when the API is empty | `job_api.api_trigger_fetch(base_url, token, portal="gupy")` |
| List → fetch-if-empty → re-list → top job | `job_api.pick_jobs_for_apply(base_url, token, ...)` |

A 401 anywhere maps to `{"error": "unauthorized"}` — the bot should surface
that and ask the human to verify the secret matches `bot.service.api-key`
(see the service-token setup below).

### curl examples (service-token setup)

```bash
# 1) generate the service token once (the SAME value goes on both sides):
openssl rand -hex 24

# 2) use it (backend bot.service.api-key must hold the same secret, issue #47):
TOKEN="<the same secret>"   # or stored in ~/.hermes/profiles/jobhunter-bot/api-token.txt

# List jobs with a contact email (the API's real query param is hasEmail):
curl -s http://localhost:8080/api/jobs?hasEmail=true -H "X-Bot-Token: $TOKEN"

# Single job detail (id, title, company, url, description, postedAt, source, contactEmail):
curl -s http://localhost:8080/api/jobs/7 -H "X-Bot-Token: $TOKEN"

# Trigger a scrape when the list is empty (default portal gupy):
curl -s -X POST http://localhost:8080/api/jobs/fetch/gupy -H "X-Bot-Token: $TOKEN"
```

The token lives in bot memory (`~/.hermes/profiles/jobhunter-bot/api-token.txt`
or `JOBHUNTER_API_TOKEN`); no credential is ever stored in this repository.

### How this changes the browser skill's task decisions

- `scrape_listings` → first call `GET /api/jobs`; scrape the portal **only** when
  the API returns no listings for the query.
- `apply` → when the job id is known from the API (`--job-id`) the browser
  session/tab resumes the application form directly — no browsing around to find
  the listing again.

### Listing jobs (API-first — the fixed procedure)

The bot's job-discovery flow is **fixed**: run this sequence exactly once per
request, present the result, and stop. Do not improvise, retry, or loop back
to "find more" — a surprising result is reported to the user, never repaired
by re-browsing portals within the same flow.

1. **List** — `GET /api/jobs?hasEmail=true&minScore=<threshold>` with the
   `X-Bot-Token` header (`job_api.api_list_jobs`). `hasEmail=true` is always
   sent, so only jobs with a contact email are considered.
2. **Empty → fetch + re-list once** — if the list is empty, `POST
   /api/jobs/fetch` (all providers), then re-list. Still empty → report
   "nothing to apply to" and stop — no re-fetch, no browser pass here.
3. **Join scores/analyses** — pair each listing with its stored AI analysis
   (`matchScore`, matched/missing skills, company tone) when present; a job
   without an analysis is marked `unanalyzed` — offer analysis as the next
   step instead of analyzing silently.
4. **Filter dev roles** — keep dev/engineering roles (title/skills contain
   dev, developer, software, backend, frontend, full-stack, java, ...),
   honoring bot-memory preferences (excluded companies, location,
   remote-only). Non-dev roles are dropped from the table — never applied to
   by accident.
5. **Present a ranked table** — score descending, columns:
   `# | title | company | match | contact | draft status` (draft status:
   `none` / `draft` / `applied`), followed by **observations** (strongest and
   weakest match, missing skills to address in the cover letter).
6. **Ask ONE next-step question** — e.g. *"Analyze `<job>` (match `<n>`)? Or
   draft the application email?"* — one question, then wait for the answer.

> Rule: this procedure replaces improvisation — there are **no self-correction
> loops**. If the data looks wrong, present what exists and ask the user how
> to proceed.

---

## When to use

- A targeted portal/board has **no public API** and the existing scraper for it (Gupy, InfoJobs, LinkedIn) **failed or returned empty** for the same query — the browser is the fallback.
- The app has identified a job listing on a portal that supports **in-portal applications** (form-based submission) and the user asked the bot to apply.
- The user asks to **check the status** of applications previously made through a portal (viewed / under-review / responded).
- The portal paginates or renders listings **only after JS execution** — a static fetch yields no results.

### When NOT to use

- The Job Hunter API already returns the listing (`GET /api/jobs` / `GET /api/jobs/{id}`) — **query the API BEFORE scraping** (issue #46 standing rule above).
- Gupy, InfoJobs, or LinkedIn scrapers already return the listing — use those.
- The job communicates a direct `contactEmail` (or a careers-page email) — prefer the `company-scraper` skill and email application.
- The task is **sending an email** — that is the himalaya-tool domain, **not** this skill.
- The task is **terminal / system operations** (installing packages, running shell tooling) — that is a separate concern, not this skill.

---

## Inputs

| Parameter | Type | Required | Description |
|---|---|---|---|
| `url` | string | yes | Portal or application URL to open |
| `task` | string | yes | One of: `apply`, `check_status`, `scrape_listings` |
| `application_data` | object | for `apply` | Structured data as a map of field name → value (see below) |
| `job_id` | string | for `apply` | Portal job identifier (used for idempotency + status tracking) |
| `contact_email` | string | for `apply` | The contact email associated with this application |
| `pagination` | object | for `scrape_listings` | `{ max_pages, page_size, next_selector }` |
| `timeout` | integer | no | Per-action wait timeout in seconds (default 15) |

### Application data fields

```json
{
  "name": "<string>",
  "email": "<string>",
  "phone": "<string>",
  "resume_path": "<string path to resume file>",
  "cover_text": "<string cover letter body>"
}
```

The bot receives this data already structured (from Java DTOs / the app's profile) and must map it onto the portal's form fields by inspecting labels and `name`/`id` attributes.

---

## Steps

### Task: `apply` — application-form filling

1. **Open** the application URL in the browser and wait for the form to render (default 15 s; longer if the selector for the first field is not yet present).
2. **Identify** all required form fields by inspecting labels / `name` / `id` attributes — do not assume a field order.
3. **Map** `application_data` fields onto the form fields (name, email, phone, resume file input, cover-letter textarea).
4. **Fill step by step** — fill one field, then the next. After each fill, report to the user what was set.
5. **Confirm each step** — before moving across *sections* of a multi-page form, confirm with the user. Never proceed to the next page/section without explicit confirmation.
6. **Final confirmation** — when the form is fully filled but before clicking the **submit** button, present a summary of all entered values and ask for explicit user confirmation.
7. **Submit only after** the user confirms. Never auto-click submit.
8. **Record** the application outcome (job_id, contact_email, date, portal) to memory for status tracking and idempotency.

### Task: `check_status` — status checking

1. **Open** the portal and locate the "my applications" / "minhas candidaturas" area (presence of pagination or filter if needed).
2. **Find** the specific application by the recorded `job_id` (or job title as fallback).
3. **Report** the observed state: `viewed`, `under_review`, `responded`, `withdrawn`, or `unknown` — with the surrounding text shown to the user.
4. Optionally **update memory** with the new status.

### Task: `scrape_listings` — dynamic-site scraping

1. **Open** the listings URL and **wait** for the JS-rendered list to appear (use a stable selector, e.g. the first listing card).
2. **Pagination** — walk pages up to `max_pages` by activating the *next* selector; stop early if the next element is missing or content repeats.
3. **Extract** per-listing: title, company, location, link, and any posted-date text.
4. **Output** the listings as a JSON array (schema below) for the caller to feed into the normalization/analysis pipeline.

---

## Output conventions

### Generic output

All tasks return a JSON object. `ok` is always present; `data` carries task-specific fields.

```json
{
  "ok": true,
  "task": "apply | check_status | scrape_listings",
  "url": "<input url>",
  "data": { "... task-specific ..." },
  "confirmationRequired": true
}
```

- `confirmationRequired` is `true` for `apply` (the bot must wait for the user before submitting).
- On error: `ok: false` + `error` object (see Error conventions).

### `apply` data

```json
{
  "jobId": "<string>",
  "contactEmail": "<string>",
  "filledFields": ["name", "email", "phone", "resume", "cover_text"],
  "skippedFields": ["<field the bot could not map>"],
  "readyForSubmission": true
}
```

### `check_status` data

```json
{
  "jobId": "<string>",
  "status": "viewed | under_review | responded | withdrawn | unknown",
  "evidence": "<raw text snippet observed on the page>"
}
```

### `scrape_listings` data

```json
{
  "listings": [
    {
      "title": "<string>",
      "company": "<string>",
      "location": "<string>",
      "url": "<absolute url>",
      "postedAt": "<string or null>"
    }
  ],
  "pagesVisited": 2
}
```

---

## Error conventions

| Situation | Behavior |
|---|---|
| Portal unreachable / timeout | Return `{"ok": false, "error": {"code": "timeout", "url": "..."}}` |
| Form field not found | Return `{"ok": false, "error": {"code": "field_not_found", "field": "<name>"}}` |
| JS never renders (selector not found) | Return `{"ok": false, "error": {"code": "render_timeout", "selector": "<sel>"}}` |
| Apply already recorded for (job_id, contact_email) | Return `{"ok": false, "error": {"code": "already_applied"}}` — do NOT open the form (see Guardrails) |
| Previous draft is a NO_APPLY/refusal | Return `{"ok": false, "error": {"code": "refusal_draft_blocked"}}` — do NOT send as an application |
| Login/session required | Return `{"ok": false, "error": {"code": "auth_required", "url": "<the auth url>"}}` (see Auth page handling below) |
| User asked to submit but confirmation not given | Return `{"ok": false, "error": {"code": "not_confirmed"}}` |

---

## Guardrails (from related issues — MUST be honored)

- **#27 idempotency** — never apply twice to the same `(job_id, contact_email)`. Before opening an apply flow, check memory and existing records; if an application to that pair already exists, stop with `already_applied`.
- **#28 never send a refusal** — if the paired analysis/generation produced a `NO_APPLY` / refusal draft (`email-no-apply-refusal.md`), that draft must **never** be submitted as an application through this skill. Stop with `refusal_draft_blocked`.
- **#31 memory consultation** — before applying, consult bot memory for user preferences (e.g. do-not-apply list, salary/benefit preferences, location constraints). Respect any preferences found.
- **Explicit confirmation** — the bot never clicks submit without an explicit, per-step, and final user confirmation. `confirmationRequired` must be honored.
- **#40 never fill credentials on auth pages** — the moment `auth_guard.check_navigation()` flags an auth page (`auth_required`), the bot **stops immediately** and asks the user to log in manually at the returned URL. It must **never** fill email/password fields, even if the form labels are visible.
- **Complement only** — if a primary scraper already returned this listing, the browser skill should not be used to re-scrape it.

---

## Memory sync convention

Application records and statuses may be persisted by the bot to:

```
~/.hermes/profiles/jobhunter-bot/memails/applications/<job_id>.json
```

```json
{
  "jobId": "<string>",
  "contactEmail": "<string>",
  "portal": "<string>",
  "appliedAt": "<ISO timestamp>",
  "status": "viewed | under_review | responded | withdrawn | unknown",
  "lastCheckedAt": "<ISO timestamp>"
}
```

The `(job_id, contact_email)` pair is the idempotency key for #27. This follows the memory convention established in `hermes-agent-integration.md` (#27/#31).

---

## Limitations

- **Email sending is out of scope** — himalaya (configured on the bot profile) handles email delivery; this skill never sends mail.
- **Terminal / system operations are out of scope** — installing browsers, managing systemd, or general shell tooling is a separate concern and not addressed here.
- **Does not replace primary scrapers** — Gupy, InfoJobs, and LinkedIn remain the first-line sources; the browser is the fallback when they fail or the target is not covered.
- **Session/login dependencies** — portals that require authentication stop with the `auth_required` error (see Auth page handling below); managing credentials is the user's responsibility.
- **Page-structure fragility** — selectors rely on portal markup; a portal redesign may require the bot to adapt by inspecting the new DOM.

---

## Auth page handling (issue #40)

During **free navigation** (any `apply` / `check_status` / `scrape_listings` step),
the bot can be redirected to a login/signin/auth page — e.g. Gupy's
`/candidates/auth/...`, or any portal's `/login`, `/signin`, `/auth` area.
Handling is provided by the self-contained `auth_guard.py` module shipped with
this skill (stdlib-only: `re`, `urllib.parse`, `typing` — no cross-skill imports).

### Detection

```python
from auth_guard import check_navigation

result = check_navigation(current_url, job_id=job_id)
```

`is_auth_url(url)` (also exposed by the module) matches **whole path segments**
boundary-checked and case-insensitively, ignoring the query string and fragment:

| URL | Detected? | Why |
|---|---|---|
| `https://portal.example.com/login` | ✅ | segment `login` |
| `https://portal.example.com/signin` | ✅ | segment `signin` |
| `https://jobs.gupy.io/candidates/auth/login` | ✅ | segment `auth` |
| `https://portal.example.com/login?next=/jobs` | ✅ | path segment wins; query ignored |
| `https://portal.example.com/jobs/123-login-dev` | ❌ | whole segment is `123-login-dev`, not `login` |
| `https://portal.example.com/authentication-page` | ❌ | whole segment is `authentication-page`, not `auth` |
| `https://portal.example.com/jobs?next=/login` | ❌ | query string ignored |

### Stop protocol (NEVER-FILL RULE — core of #40)

1. **Detect** — run `check_navigation(current_url)` before every navigation step.
2. **Stop immediately** — if the result is `{"ok": false, "error": {"code": "auth_required", ...}}`, do **not** proceed, do **not** retry.
3. **Never fill credentials** — the bot must **not** fill the email/password fields even if they are visible on the page. The payload contains no fill step and `NEVER_FILL_CREDENTIALS = True` in `auth_guard.py` is the standing rule.
4. **Ask the user** — output the `auth_required` error (PT-BR `detail`, plus `url` — the direct auth page) and ask the user to **log in manually at that URL**.
5. **Resume after login** — when the user confirms the manual login, restart the task from the original target URL; the persisted browser session is reused (see the #39 CDP optional setup below).

```json
{
  "ok": false,
  "error": {
    "code": "auth_required",
    "detail": "Página de autenticação detectada. O bot não preenche credenciais: faça login manualmente nesta página e confirme para continuar.",
    "url": "https://jobs.gupy.io/candidates/auth/login",
    "job_id": "<id when the caller supplied it>"
  }
}
```

> Same-page detection mirrors the semantics of the job-application skill's
> `navigation.py` (issue #38): whole-segment, case-insensitive, query-ignored.
> `auth_guard.py` is deliberately self-contained — each skill installs
> standalone to `skills/<name>/`, so cross-skill imports would break at install
> time.

### Optional: persistent session via #39 CDP setup

If the environment also runs the CDP/browser-recovery setup from issue #39
(persistent Chromium `--user-data-dir`, default `~/.chromium-profile-cdp`), the
manual login performed here survives restarts: **once** the user logs in, later
runs on the same profile should reach the application form directly without
another `auth_required` stop.