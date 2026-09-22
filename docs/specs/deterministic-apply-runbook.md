# Deterministic Phase-Gated Apply Runbook

Spec for issue #72. Companion to `mcp-apply-loop.md` (executor loop) and
`job-application-auth-loop.md`. This spec turns the human-language contract in
`skills/job-application/SKILL.md` into a deterministic, phase-gated runbook:
the LLM fills in and verifies *within* each phase, while phase order, allowed
tools, and pass criteria live in code/config — not in prose.

## 1. Problem

The portal-apply flow depends on LLM reasoning to choose the next tool call.
Observed drift: ad-hoc CDP scripts instead of the skill executor loop, acting
through the wrong browser session (cloud instead of the authenticated local
Chromium), re-litigating settled findings (URL truncation), and proposing
config changes without evidence. Free-form reasoning plus many available tools
produces loops instead of one-shot execution.

## 2. Current state (grounding)

- `skills/job-application/apply.py` (planner, stdlib-only): gate order is
  portal allow-list → profile load/validate → refusal → API resolution →
  browser recovery → session expiry → idempotency → intent emission. Emits
  `{"intent": ..., "dry_run": ...}` only; never writes records.
- `skills/job-application/classify.py`: pure page classifier, 7 kinds
  (`form | triagem | review | sucesso | auth | erro | start`), hard stops first.
- `skills/job-application/verdict.py`: sole writer. `applications/<job_id>.json`
  only for verified `SUBMIT_OK`; `attempts/<attempt_id>/<ts>.json` for every run.
- `skills/job-application/intent.py`: intent contract, portal allow-list
  (`gupy | infojobs`), `DEFAULT_MAX_STEPS=25`, no selectors/step lists.
- `skills/job-application/job_api.py`, `navigation.py`, `ax_tree.py`: API
  client (header `X-Bot-Token`), URL/session guards, AX-tree helpers.
- `skills/job-application/audit.py` (+ `audit_test.py`): optional **read-only**
  pre-batch consistency audit of backend lifecycleState vs `applications/`,
  `attempts/` and screenshots (issue #81, spec
  `docs/specs/consistency-audit.md`).
- Test suites: `apply_test.py`, `classify_test.py`, `intent_test.py`,
  `job_api_test.py`, `navigation_test.py`, `ax_tree_test.py`, `verdict_test.py`
  (all stdlib `unittest`).
- **Known divergence (fixed by this spec, §5):** `SKILL.md` describes
  quarantine at `attempts/quarantine/` plus `MANIFEST.md`, but
  `write_attempt_log` writes `attempts/<attempt_id>/<ts>.json`.

## 3. Design

### 3.1 `preflight.py` (new, stdlib-only, same style as `apply.py`)

Runs before any intent, **by default** (default-on): the planner runs the
gate on `<memory-dir>/preflight-config.json` on every real run, dormant only
on `--dry-run` without an explicit `--preflight-config`. The documented
opt-out is `--skip-preflight-check` (diagnosed cases only). Checks, in order,
each returning facts plus an exit code (`0` pass, `1` fail with a
machine-readable error):

1. Chromium process for the dedicated profile is running.
2. CDP endpoint from the profile config answers `/json`.
3. The configured browser tool is attached to that same CDP endpoint
   (tab list matches; a listening port alone is NOT proof).
4. Gupy session is valid (authenticated marker, no `/candidates/auth`
   redirect).

The bot proceeds only on full pass. On failure it stops and reports the
failing check verbatim — no improvisation, no fallback browsers, no scripts.

### 3.2 Phases and gates

```
preflight → intent → navigate → fill → review → submit → record
```

- Each phase declares its required evidence (current AX snapshot refs,
  `classify_page` kind, filled-field provenance). A transition without the
  required evidence is refused by the runbook.
- `auth` classification is a hard stop everywhere: never fill, never submit.
- Submit requires the review gate: all required answers grounded (see #73 for
  the answer policy), explicit user confirmation for optional consents.
- `verdict.py` remains the sole writer; backend `api_record_applied` stays
  best-effort and never fails the local record.

### 3.3 Tool allow-list per phase (documented in `SKILL.md`)

During apply the bot may use, per phase: browser tool (+ `classify.py`),
`apply.py` (plan only), `verdict.py` (record only), API reads. Generic shell,
script creation, and config edits are out of scope for the run — the wrong
path must be impossible, not merely discouraged. Hermes-side enforcement of
per-phase tool visibility is an integration follow-up, not part of this spec.

### 3.4 Fail-closed

Any ambiguity — dubious session, field with no profile source, missing tool,
unrecognized page — stops the run and asks. The default is never to try
another means silently.

## 4. Test plan (TDD, RED → GREEN → REFACTOR)

- New `preflight_test.py`: each check pass/fail, exit codes, error shapes,
  port-open-but-detached case, auth-redirect case.
- Extend `apply_test.py`: planner refuses to emit intent on preflight fail.
- Extend `verdict_test.py` + `classify_test.py` only where gate semantics
  change; no behavior change to existing verdict rules.
- Plain `unittest`, no Spring, no network (inject CDP/API fakes as the
  existing suites do).

## 5. Canonical attempt layout (resolves §2 divergence)

- Verified applies: `applications/<job_id>.json` (unchanged schema).
- Every run: `attempts/<attempt_id>/<ts>.json` (unchanged schema).
- Quarantine stays a *status* inside the attempt record, not a separate
  directory tree; `SKILL.md` quarantine/`MANIFEST.md` section is updated to
  match. No migration of existing records.

## 6. URL and job_id integrity validation (issue #82)

Gupy job publicIds are urlsafe-base64: the only legal characters are
`A-Za-z0-9_-` plus a trailing `=` padding. A literal `.` — and therefore a
`...` elision — is **impossible** in a genuine Gupy slug.

- **Source rule:** job URLs come from the Job Hunter API (`--from-api` /
  `--job-id`) or pass the integrity validation. They are **never hand-typed**
  — an LLM elides the long opaque base64 publicId as `...`, and a truncated
  URL navigates to the listing page instead of the detail (the #82 root
  cause). `derive_job_id` keeps accepting the URL verbatim; validation is
  the gate that refuses the corrupt result.
- **Validation at entry (before intent emission):** `apply.py` rejects any
  Gupy URL whose derived slug contains `.` (hence `...`) with a JSON error
  `{"error": "corrupt_gupy_slug", ...}` — exit code 1, the same failure mode
  as `invalid_job_url`. No intent is emitted and nothing is written.
- **Validation of the resolved URL at intent emission:** `derive_job_id`
  over-matches the FIRST `/jobs/<slug>/` segment, so a derived id can be
  clean while the url field (the API/DB value, in every input mode
  `--job-url` / `--job-id` / `--from-api`) still carries a corrupt LAST slug.
  The single intent-emission point therefore also scans the resolved url:
  for Gupy hosts only, the last path segment is checked with the same
  integrity rule (`corrupt_gupy_url_slug`), rejecting with the same
  `corrupt_gupy_slug` JSON error and exit 1, before any intent file is
  written. Non-Gupy urls are unaffected.
- **Validation at record-write time:** `verdict.py` (sole writer of
  `applications/` and `attempts/`) and `answer.py` (`answers/`) refuse to
  persist a record whose `job_id` contains `.`. The marker surfaces through
  the record contract validator as `job_id.corrupt_gupy_slug`; the writer
  returns the refusal dict and writes nothing.
- Audit identity (item D): the audit prefers the applications record's
  `backend_job_id` (numeric) over the slug when resolving backend identity
  — a slug-format miss (InfoJobs numeric vs Gupy base64) or a corrupt slug
  must not manufacture `applied_without_backend` / `backend_submitted_without_application`
  phantoms.
- Non-Gupy portals (InfoJobs numeric slug `755694375`, LinkedIn
  `/jobs/view/`) are unaffected: numeric and normal slugs keep working.

## 7. Acceptance criteria (#72)

- In a fresh conversation, the bot uses the correct (authenticated) Chromium
  or stops with a clear instruction — never proceeds in the wrong browser.
- No ad-hoc scripts created during apply runs.
- Every phase transition backed by current-snapshot evidence.
- Ambiguity stops the run instead of triggering workarounds.

## 8. Out of scope

- Hermes auto-attach integration (follows separately if needed).
- Profile-facts answer policy (issue #73; plugs into the review gate).
- Email funnel, scoring, enrich, LinkedIn (manual by design).
