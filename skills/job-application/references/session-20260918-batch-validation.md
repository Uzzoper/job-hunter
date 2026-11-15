# Session corrections — batch validation & eligibility rules

> Corrections and workflow rules established during the 2026-09-18 session.

---

## 1. URL truncation is a display artifact — never "fix" it

**Test**: `SELECT id, url LIKE '%...%', length(url) FROM jobs WHERE id IN (...)` — if 0 rows match, URLs are intact.

**Rule**: When a URL prints as `eyJqb2...sIn0=`, the `...` is terminal truncation. The full URL is in the DB. Write it to a file and read it back; never attempt to decode/rebuild the base64 segment.

---

## 2. Batch workflow (user-mandated sequence)

For applying to multiple jobs:

1. `POST /api/jobs/fetch/gupy` to get fresh listings
2. `GET /api/jobs?minScore=70` to list high-score jobs
3. Process **one by one** (never batch scripts):
   - Navigate to job page
   - Check eligibility (superior completo, years of experience, required stack)
   - If eliminatório → mark ELIGINALITY_BLOCK, skip without submitting
   - If live and eligible → apply
4. Partial report at end of each application
5. If a vacancy is gone from the public listing → NOT_AVAILABLE, skip

---

## 3. Screening answers (profile defaults, confirmed)

Values come from bot memory (`memories/MEMORY.md`) and are never committed
to this public repository:

| Question | Answer |
|---|---|
| *(screening question)* | `<declared value from memory>` |

**If a question has no declared source, ask the human before submitting.**

---

## 4. Branch management

- Only `dev` branch exists (no `main`)
- `git pull origin dev` for updates
- Never assume `main` exists

---

## 5. Job lifecycle states

- SUBMITTED: application sent
- ELIGIBILITY_BLOCK: rejected before submission (eliminatory criteria)
- NOT_AVAILABLE: vacancy removed from public listing
- REJEITADO: submitted but rejected by company

---

## 6. Backend API status check

- Backend HTTP 200 = healthy
- Hermes Gateway HTTP 401 is expected (no key needed for browser-only applies)
- If backend 401 → check Nous subscription / API key
