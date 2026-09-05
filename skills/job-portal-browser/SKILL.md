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

## When to use

- A targeted portal/board has **no public API** and the existing scraper for it (Gupy, InfoJobs, LinkedIn) **failed or returned empty** for the same query — the browser is the fallback.
- The app has identified a job listing on a portal that supports **in-portal applications** (form-based submission) and the user asked the bot to apply.
- The user asks to **check the status** of applications previously made through a portal (viewed / under-review / responded).
- The portal paginates or renders listings **only after JS execution** — a static fetch yields no results.

### When NOT to use

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
| Login/session required | Return `{"ok": false, "error": {"code": "auth_required"}}` |
| User asked to submit but confirmation not given | Return `{"ok": false, "error": {"code": "not_confirmed"}}` |

---

## Guardrails (from related issues — MUST be honored)

- **#27 idempotency** — never apply twice to the same `(job_id, contact_email)`. Before opening an apply flow, check memory and existing records; if an application to that pair already exists, stop with `already_applied`.
- **#28 never send a refusal** — if the paired analysis/generation produced a `NO_APPLY` / refusal draft (`email-no-apply-refusal.md`), that draft must **never** be submitted as an application through this skill. Stop with `refusal_draft_blocked`.
- **#31 memory consultation** — before applying, consult bot memory for user preferences (e.g. do-not-apply list, salary/benefit preferences, location constraints). Respect any preferences found.
- **Explicit confirmation** — the bot never clicks submit without an explicit, per-step, and final user confirmation. `confirmationRequired` must be honored.
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
- **Session/login dependencies** — portals that require authentication will return `auth_required`; managing credentials is the user's responsibility.
- **Page-structure fragility** — selectors rely on portal markup; a portal redesign may require the bot to adapt by inspecting the new DOM.