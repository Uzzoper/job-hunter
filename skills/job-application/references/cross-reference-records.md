# Cross-Referencing Application Records to Jobs

When auditing `applications/` records against the backend, the API may return 404 for old jobs. Use this fallback chain.

## Record `job_id` formats

| Format | Example | Source |
|--------|---------|--------|
| Integer | `1000013442` | Legacy numeric IDs (InfoJobs, old Gupy) |
| Base64 string | `eyJqb2...sIn0=` | Gupy slug — decode to `{"jobId":N,"source":"gupy_portal"}` |
| Numeric string | `"<numeric-app-id>"` | string but all digits, seen on some portals |

## Lookup chain

1. **API**: `GET /api/jobs/{job_id}` — works for jobs currently in the API cache
2. **Local DB**: `SELECT id, title, company FROM jobs WHERE id=?` — works for numeric IDs still in the local SQLite
3. **URL match** (Gupy base64 only):
   - Decode base64 → extract `jobId` field
   - `SELECT id, title, company FROM jobs WHERE url LIKE '%<jobId>%'`
   - Gupy canonical URL: `https://<company>.gupy.io/jobs/<numericId>?jobBoardSource=gupy_public_page`
4. **Slug match**: Some records store the full URL slug — match against `url` column patterns

## Quarantine criteria

A record goes to `attempts/quarantine/` when it lacks ALL of:
- `verdict: SUBMIT_OK`
- Existing screenshot file on disk
- Evidence text containing "sucesso" or "finalizada"

Exceptions: records where `evidence.page == "sucesso"` (dict format, even without screenshot file) should stay.

## Numeric-string pattern (2026-09-11)

A complete application record with `verdict: SUBMIT_OK` and `evidence` dict pointing to `.../applications/<app-id>/success`, but the screenshot file no longer exists in cache (cleaned up). The record itself is valid proof — do NOT quarantine it.
