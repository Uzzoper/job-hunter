# Consistency Audit (issue #81)

Spec for a read-only cross-check of applied-job state before a batch.
Companion to `deterministic-apply-runbook.md` (#72) and `mcp-apply-loop.md`
(runner) — same pure-`stdlib` TDD style (RED → GREEN → REFACTOR) as
`preflight.py` / `verdict.py`.

## 1. Problem

Applied-job state lives in **four places** that can silently diverge:

1. Backend `jobs.lifecycleState` (funnel source of truth, exposed by the Job
   Hunter API on `GET /api/jobs` as the `lifecycleState` field).
2. `applications/<job_id>.json` (bot memory, written only by `verdict.py` on
   verified `SUBMIT_OK`).
3. `attempts/<attempt_id>/<ts>.json` (bot memory, written for EVERY run —
   the quarantine record for non-verified outcomes).
4. `screenshots/<job_id>.png` (post-submit evidence convention).

Observed divergences (real incidents, all found reactively during sessions):

- **Job 609 orphan contradiction**: `applications/609.json` said `applied`,
  `attempts/...` said `SESSION_EXPIRED`, backend `lifecycleState=null`,
  screenshot missing. Had to be quarantined manually.
- **Missing attempts records**: past sessions wrote applications without
  attempts.
- **Backend-`SUBMITTED` without a local record**: the backend was marked
  submit without any `applications/` file.
- **NOT_AVAILABLE without record**: verdict printed as unavailable but nothing
  persisted.

There is no systematic way to catch the next class of divergence before a
batch starts. This spec adds a read-only audit the operator (or later a
pre-batch gate) can run.

## 2. Current state (grounding)

- `verdict.py` is the SOLE writer of `applications/<job_id>.json` (status
  `applied`, only on verified `SUBMIT_OK`) and
  `attempts/<attempt_id>/<ts>.json` (every run; quarantine = a status inside
  the attempt record, not a directory).
- Applied record schema (contract `AppliedRecordContract`): `job_id`,
  `contact_email`, `portal`, `applied_at`, `screenshot_path`, `status`
  (`"applied"`), `verdict` (`"SUBMIT_OK"`), `evidence` (+ optional
  `backend_record` / `backend_job_id`).
- Attempt record schema (contract `AttemptRecordContract`): `attempt_id`,
  `job_id`, `job_url`, `portal`, `started_at`, `ended_at`, `outcome`, `reason`
  (+ optional `final_page`, `trace`, `screenshot_path`, `manual_url`,
  `verdict`).
- Backend `JobResponse` carries `id` (Long), `url`, `lifecycleState`
  (`ApplicationLifecycle` enum string — e.g. `SUBMITTED`, or `null` when the
  job has no lifecycle yet). Note: the list endpoint filters nothing on the
  lifecycle axis; `excludeApplied=true` is NOT requested by the audit.
- Bot auth is the static service token (issue #47): `X-Bot-Token` header,
  resolved by `job_api.resolve_token` (flag > `JOBHUNTER_API_TOKEN` >
  `<profile-dir>/api-token.txt`).
- Screenshot path conventions documented today are inconsistent:
  - SKILL.md record example: `<memory-dir>/screenshots/<id-slug>.png`.
  - mcp-apply-loop.md: `<memory>/screenshots/<job_id>-<ts>.png` (timestamped).
  - issue #81 text: `~/.hermes/profiles/jobhunter-bot/screenshots/<id>.png`
    (profile root, NOT under `memails/`).
The audit therefore resolves screenshot existence through **both** the
conventional `<screenshots_dir>/<job_id>.png` AND the recorded
`screenshot_path` field (exact basename resolved against the screenshots
dir), and detects orphans by "referenced by a record OR named
`<local_job_id>.png` for a job with any local record" — the backend knowing
a numeric id does NOT legitimize a screenshot with no local evidence trail,
and slug-vs-timestamp separators are never guessed.

## 3. Design — `skills/job-application/audit.py` (new, stdlib-only)

Same style as `verify.py`/`preflight.py`: passive CLI with JSON-printing and
injected dependencies so the unit tests never touch the network.

### 3.1 Sources read (all READ-ONLY)

| Source | Location | Read |
|---|---|---|
| Backend lifecycle | `GET <base>/api/jobs` → list of `JobResponse` (X-Bot-Token) | HTTP |
| Applications | `<memory-dir>/applications/*.json` | filesystem |
| Attempts | `<memory-dir>/attempts/**/*.json` (flat and `attempts/<id>/<ts>.json`) | filesystem |
| Screenshots | `<screenshots-dir>/*.png` (aggregate of the evidence convention) | filesystem |

The audit NEVER writes, moves, renames or deletes anything — reading only.

### 3.2 CLI shape

```
python3 audit.py [--api-base-url <url>] [--api-token <token>]
                 [--profile-dir <dir>] [--memory-dir <dir>]
                 [--screenshots-dir <dir>]
```

- `--api-base-url` — default `http://localhost:8080`.
- Token: `--api-token` > `JOBHUNTER_API_TOKEN` env > `<profile-dir>/api-token.txt`
  (default profile dir `~/.hermes/profiles/jobhunter-bot`), via
  `job_api.resolve_token`. Missing → `{"error": "missing_api_token", ...}`
  (exit 1).
- `--memory-dir` — default `~/.hermes/profiles/jobhunter-bot/memails`.
- `--screenshots-dir` — default `<memory-dir>/screenshots`; overridable for
  environments where the evidence lives at the profile-root path cited in
  issue #81 (`~/.hermes/profiles/jobhunter-bot/screenshots`).

### 3.3 Identity mapping (portal slug ↔ backend job)

Local records are keyed by the portal job slug (`job_id` from
`apply.derive_job_id(job_url)`, or the numeric/base64 Gupy key). Backend jobs
carry a numeric `id` and a `url`. The audit builds the known-keys set per
backend job as:

```
{str(job.id)}  ∪  {apply.derive_job_id(job.url)}
```

The cross-checks compare against that set (so "609" matches both
`id=609` and a URL slug "609"). Empty/`None` derivations are dropped.

### 3.4 Checks (one line per divergence)

| Code | Trigger | Line detail (source A vs source B) |
|---|---|---|
| `applied_without_backend` | `applications/<k>.json` has `status=applied` and the backend has no lifecycle `SUBMITTED` for key `k` | `job=<k> applications=applied backend_lifecycle=<null\|value>` |
| `applied_without_successful_attempt` | applied record exists but NO attempt for its key has a success verdict (`outcome == "SUBMIT_OK"` or `verdict.submitted is True`) | `job=<k> applications=applied attempts=<aids…> outcomes=<…> [quarantine]` — the quarantine annotation lists exactly the attempt records that failed/await review |
| `applied_without_screenshot` | applied record exists but no screenshot resolves (see §2) | `job=<k> applications=applied screenshots=none` |
| `backend_submitted_without_application` | backend key has `lifecycleState == "SUBMITTED"` and no `applications/<k>.json` | `job=<k> backend=SUBMITTED applications=missing` |
| `attempt_unknown_job` | an attempt (any file) references a `job_id` key unknown to the backend | `job=<k> attempts=<files…> backend=unknown` — ONE line per unknown key, never per attempt file |
| `orphan_screenshot` | a PNG in the screenshots dir is referenced by NO record (`screenshot_path` basename) AND is not named `<local_job_id>.png` for any LOCAL record job (application or attempt) | `screenshot=<file> applications=none attempts=none` |

Quarantine (non-verified attempt records) is EXPECTED state: it is annotated
in the divergence lines it contributes to (`[quarantine]`), but a quarantine
record alone is NEVER flagged as a divergence — no double-flagging.

### 3.5 Output and exit codes

- Divergences present → print one line per divergence (readable, greppable)
  followed by `SUMMARY: <n> divergence(s) across <m> job(s)`, exit **1**.
- No divergences → print `SUMMARY: 0 divergences (all sources consistent)`,
  exit **0** (the future pre-batch gate contract).
- Usage / config / backend errors → clean JSON error
  (`{"error": <code>, "detail": ...}`), exit **2** (usage) or **1**
  (api/token), never a traceback.

## 4. Test plan (TDD, RED → GREEN → REFACTOR)

New `skills/job-application/audit_test.py` (plain `unittest`, no Spring, no
network; fixtures are `tempfile.TemporaryDirectory` trees, NEVER the real
`~/.hermes`):

- Every divergence class of §3.4 detected through the CLI (`run()` with an
  injected backend `fetch`) → exit 1 and a readable line.
- Consistent fixture → exit 0.
- Quarantine annotation: exactly ONE divergence line when an applied record's
  only attempt is a failed/quarantine attempt (never a second line flagging
  the attempt itself).
- Read-only guarantee: full fixture tree (paths + bytes) compared before and
  after a `run()` — the audit must not touch the sources.
- Corrupt/unreadable record files and empty dirs degrade gracefully (no
  crash; missing data surfaces as the corresponding divergence).

## 5. Acceptance criteria (#81)

- The audit lists every mismatch between the backend lifecycle, the local
  applied records, the attempt/quarantine trail and the screenshots — one read
  over the memory dirs plus one `GET /api/jobs`.
- Read-only: nothing is ever written, moved or deleted.
- Exit 0 clean / exit 1 divergences — gateable by a future pre-batch step.
- Runs on the VM where the bot memory dirs exist; tests use tmp fixtures.

## 6. Out of scope

- Writing/quarantining/repairing divergent records — the audit ONLY reports.
- Per-job API detail calls (`GET /api/jobs/{id}`) — the list endpoint already
  carries `lifecycleState`.
- Reconciliating against `answers/` or `preflight` state.
- Hermes-side scheduling of the audit as a gate (integration follow-up).