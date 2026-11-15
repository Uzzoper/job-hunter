# URL Truncation Repair (2026-09-22)

## Problem

Gupy URLs with base64 slugs (e.g., `eyJqb2...sIn0=`) get truncated when hand-typed or when the scraper persists a bad value. This corrupts `job_id` in local records, making navigation impossible and breaking cross-referencing with the backend (which uses numeric ids).

**Display elision is NOT truncation — check before declaring a slug corrupt.**
Tool output (terminal results, `read_file`, API JSON echoed into a tool result)
collapses long strings by inserting a literal `...`, so a perfectly valid base64
slug can render as `eyJqb2...sIn0=` while the stored value is complete. Confirm
against the stored value, never the rendered one: compare `len(url)` with the
rendered length, or dump the codepoints (`[hex(ord(c)) for c in url]`). A
genuinely truncated slug is dead — navigating to it redirects to the site root
(`/`) instead of the job page. Only a `...` present in the STORED value is
corruption.

## Real Cases

### Job 421 (construmarket)

- Record: `applications/eyJqb2...sIn0=.json` with `backend_job_id: 421`
- Evidence: screenshot `421.png`, `evidence.url` pointed to construmarket
- Repair: copy to quarantine, rewrite `job_id` to `"421"`, rename to `421.json`

### Job `<id>` (`<company>`)

- Record: `applications/quarantine/609.json` (already quarantined)
- Reason: applied locally but backend `lifecycleState=null`, screenshot missing, attempts show `SESSION_EXPIRED`

### Attempts NOT_AVAILABLE with truncated URLs

- `attempts/<n>.json` (`<company>`) — URL: `https://<company>.gupy.io/job/eyJqb2...sIn0=`
- `attempts/<n>.json` (`<company>`) — URL: `https://<company>.gupy.io/job/eyJqb2...FsIn`
- `attempts/<n>.json` (`<company>`) — URL: `https://<company>.gupy.io/job/eyJqb2...sIn0=?jobBoardSource=gupy_portal`
- `attempts/<n>.json` (`<company>`) — URL: `https://<company>.gupy.io/job/eyJqb2...sIn0=`

All four are `NOT_AVAILABLE` outcomes — the bot navigated to a non-existent URL and the job wasn't found.

## Repair Procedure

1. **Verify the true job id** — check `backend_job_id`, `screenshot_path`, and `evidence.url` to confirm which numeric backend job the record belongs to.
2. **Copy to quarantine** — copy the file to `applications/quarantine/` with a `quarantine_reason` field.
3. **Rewrite the original** — set `job_id` to the numeric backend id, rename the file to `<numeric_id>.json`.
4. **Retriage affected attempts** — for attempts with `...` in the URL, fetch `GET /api/jobs/{id}` for the clean URL, then re-run triage (score, remote, eliminatory). If the API URL is also truncated, the job is unresolvable — report and skip.

## Prevention

- **NEVER type URLs by hand.** Always use `--job-id <id>` or `--from-api`.
- The planner fetches the URL from the API, so truncation only happens when the backend has a stale truncated URL (scraper bug).
- Formado and RG are ALWAYS eliminatory — no exceptions (check declared education status in bot memory).
