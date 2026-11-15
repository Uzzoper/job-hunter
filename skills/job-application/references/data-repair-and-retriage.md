# Data Repair and Retriage Workflow

> Discovered in production (2026-09-22). Read-only repair of corrupted job records
> and retriage of false-negative NOT_AVAILABLE attempts. All operations are
> filesystem-only on the bot memory dir — no backend writes, no browser navigation.

---

## 1. Corrupted `job_id` Repair (URL Truncation)

**Symptom**: `applications/<truncated-slug>.json` has `job_id` like `eyJqb2...sIn0=`
(base64 slug with `...` elision from hand-typed or truncated URL). The record's
`backend_job_id` points to a valid numeric backend job.

**Detection**:
```bash
# Find records with truncated job_ids in filename
find ~/.hermes/profiles/jobhunter-bot/memails/applications/ -name '*...*'

# Verify via API that backend_job_id is clean
curl -s -H "X-Bot-Token: $(cat ~/.hermes/profiles/jobhunter-bot/api-token.txt)" \
  "http://localhost:8080/api/jobs/<backend_job_id>" | python3 -c "
import json,sys; d=json.load(sys.stdin); print('url:', d.get('url')); print('length:', len(d.get('url','')))
"
```

**Repair Steps** (read-only except for the rename):
1. Copy the corrupted record to `applications/quarantine/` with a `quarantine_reason` field.
2. Rewrite the original file's `job_id` field to the numeric backend id (e.g., `421`).
3. Rename the file from `<truncated-slug>.json` to `<numeric_id>.json`.

**Python pattern** (from session):
```python
import json
from pathlib import Path

apps = Path.home() / '.hermes/profiles/jobhunter-bot/memails/applications'
qfile = apps / 'quarantine' / 'eyJqb2...sIn0=.json'
orig = apps / 'eyJqb2...sIn0=.json'
dest = apps / '421.json'

# 1. Quarantine copy
data = json.loads(qfile.read_text())
data['quarantine_reason'] = 'job_id corrupto por URL truncada digitada na mão'
qfile.write_text(json.dumps(data, indent=2, ensure_ascii=False))

# 2. Rewrite original
orig_data = json.loads(orig.read_text())
orig_data['job_id'] = '421'  # numeric backend id
orig.write_text(json.dumps(orig_data, indent=2, ensure_ascii=False))

# 3. Rename
orig.rename(dest)
```

---

## 2. Consistency Audit (Read-Only)

**Purpose**: Cross-check the four sources of applied-job state BEFORE starting a batch.

```bash
cd skills/job-application
python3 audit.py \
  --api-base-url http://localhost:8080 \
  --api-token "$(cat ~/.hermes/profiles/jobhunter-bot/api-token.txt)" \
  --profile-dir ~/.hermes/profiles/jobhunter-bot \
  --memory-dir ~/.hermes/profiles/jobhunter-bot/memails \
  --screenshots-dir ~/.hermes/profiles/jobhunter-bot/memails/screenshots
```

**Exit codes**: 0 = clean, 1 = divergences (or api/token errors), 2 = usage.

**Divergence codes** (all read-only, no repairs):

| Code | Meaning |
|---|---|
| `applied_without_backend` | `applications/<k>.json` has `status=applied` but backend lifecycleState ≠ SUBMITTED |
| `applied_without_successful_attempt` | Applied record but no attempt with SUBMIT_OK outcome (quarantine annotation) |
| `applied_without_screenshot` | Applied record but no screenshot resolves |
| `backend_submitted_without_application` | Backend lifecycleState=SUBMITTED but no local applications/ file |
| `attempt_unknown_job` | Attempt references a job_id unknown to the backend |
| `orphan_screenshot` | PNG referenced by no record and not named after any local job |

**Repair impact** (observed in session):
- Fixing `job_id` from `eyJqb2...sIn0=` to `421` removed 2 `applied_without_backend` lines
- The `backend_submitted_without_application` count dropped by 6 (records matched to backend by numeric id)

---

## 3. NOT_AVAILABLE Retriage

**Why**: Jobs marked `NOT_AVAILABLE` may be false negatives — the attempt navigated
a truncated URL (`eyJqb2...sIn0=`) that doesn't exist in the portal, not a genuinely
missing job.

**Retriage Steps** (read-only):

For each NOT_AVAILABLE attempt:
1. Read the attempt record's `job_id` (numeric) and `url` (possibly truncated).
2. Fetch the job detail from the API: `GET /api/jobs/<id>`.
3. Check the API-returned `url` field for truncation (`...` or `.` in slug).
4. If the API URL is clean, re-evaluate the job for apply:
   - `matchScore >= 70` required
   - Remote keywords in description (`remoto`, `home office`, `100% remoto`, `work from home`)
   - No eliminatory requirements (`5+ anos`, `formado`, `RG`, `CPF`, `filiação`, `naturalidade`)
   - `DADOS_PESSOAIS` → MANUAL (skip auto-apply)
5. Apply only via `--job-id <id>` or `--from-api` — NEVER type the URL by hand.

**Python check** (from session):
```python
import json, urllib.request

TOKEN = open('~/.hermes/profiles/jobhunter-bot/api-token.txt').read().strip()
req = urllib.request.Request(
    'http://localhost:8080/api/jobs/1463',
    headers={'X-Bot-Token': TOKEN, 'Accept': 'application/json'}
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())
    url = data.get('url', '')
    print(f'length(url) = {len(url)}')
    print(f"url contains '...': {'...' in url}")
```

---

## 4. Key Insight: API vs Local Records

- The backend `GET /api/jobs/{id}` returns the FULL url (115 chars, no truncation).
- The truncation exists only in local memails records (hand-typed URLs from older sessions).
- The `job_id` derived from a truncated URL preserves the slug verbatim — this is why `eyJqb2...sIn0=` appears as a filename.
- The `corrupt_gupy_slug` guard (issue #82) catches this at intent-emission time for `--job-url`, `--job-id`, and `--from-api` paths.

---

## 5. Safety Rules

- **Never write to the backend** during repair — only read via `GET /api/jobs/{id}`.
- **Never delete records** — only copy to quarantine/ and rename.
- **Always show the user the table of retriagem results before applying anything**.
- **Always use `--job-id` or `--for-api`** — typing Gupy URLs by hand caused the original corruption.
