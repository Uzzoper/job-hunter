# Spec: Planner-Intent + MCP-Executor Apply Loop

> **Layer:** `skills/job-application` (pure Python planner/classifier/verdict) · Hermes bot profile (`jobhunter-bot`, MCP executor)
> **Implementation files:**
> - `skills/job-application/intent.py` (planner intent builder — replaces selector-based plan)
> - `skills/job-application/classify.py` (pure page classifier over AX snapshot + URL)
> - `skills/job-application/verdict.py` (pure verdict evaluator + SOLE writer of applied/attempt records)
> - `skills/job-application/apply.py` (CLI rewired to emit intent JSON)
> - `skills/job-application/SKILL.md`, `bot-profile/SOUL.md` (docs updated)
> - `skills/job-application/verdict_test.py`, `skills/job-application/classify_test.py`
> - Deleted: `portals/gupy.yaml`, `helpers/gupy.py` (selector layer), `skills/cdp-daemon/`
> - Kept: `job_api.py`, `navigation.is_auth_url`, submit confirmation gate, SOUL.md gates, existing `*_test.py` gate tests

---

## Goal

Replace the selector-based, plan-time-recorded application flow with a **planner-intent + MCP-executor apply loop**:

1. The **planner** (`apply.py`) emits a small, selector-free **intent JSON** — the *what* (job, portal, profile, policy), never the *how* (no CSS selectors).
2. The **executor** (the Hermes bot driving the Playwright MCP server over the logged-in Chromium session) runs an **observe → classify → act → verify** loop:
   - `observe`: read the accessibility-tree (AX) snapshot + current URL.
   - `classify`: decide the page kind (`form` / `triagem` / `review` / `sucesso` / `auth` / `erro`).
   - `act`: fill, next, upload, or `apply-final` based on the classification and policy.
   - `verify`: after `apply-final`, confirm real success evidence.
3. The **verdict stage is the ONLY writer** of `applications/<job_id>.json` and the ONLY caller of `api_record_applied`, and it fires **only on `SUBMIT_OK` with evidence**. Every other outcome writes a retryable attempt record and never blocks a future attempt.

End state: **zero false "applied" records**. A record only exists when the browser demonstrably finished the Gupy flow, and multi-step same-URL flows (forms → triagem → review → final apply) no longer trip the old navigation loop guard.

---

## Context

### Root cause

- **Premature recording** — `skills/job-application/apply.py:1028-1033` writes `applications/<job_id>.json` and calls `api_record_applied` at **plan-generation time**, before the browser executes anything. The record is written on `--confirmed` / `--auto-apply` / `--record-applied` regardless of whether the submit actually happened. `verification_meta` is only a *hint* the bot may or may not honor — there is no post-submit verification gate.
- **Wrong granularity** — Gupy flows are multi-step. In practice they progress through several pages — candidate form, triagem/screening questions, review/summary, and a dedicated final apply button — but `build_action_plan` (via `helpers/gupy.py::apply`) emits a **single** `fill_form` + a **single** `submit` targeting `button[type='submit']`, and never emits `click_apply_button` from `helpers/gupy.py`. The plan cannot represent a multi-page portal flow.
- **Loop guard aborts legit flows** — `navigation.py` (`MAX_VISITS = 3`, URL normalized with query stripped) treats 3+ visits to the same URL as a navigation loop. Legit Gupy multi-step flows stay on the same host/page URL for many steps, so the guard fires on valid applications.
- **No official Gupy MCP** — the public API at `api.gupy.io` is recruiter-only and there is no official Gupy MCP server. The executor must therefore be a **generic browser MCP** — Playwright MCP launched with `--cdp-endpoint` reusing the persistent, logged-in Chromium session (the issue #39 profile), registered in the Hermes `mcp_servers` config.
- **Hand-rolled transport** — `skills/cdp-daemon/cdp_daemon.py` is a hand-rolled RFC6455 WebSocket client exposed over HTTP. Once the executor talks to Playwright MCP via `--cdp-endpoint`, this daemon is obsolete.

### What stays (gates and helpers that survive the redesign)

| Kept | Why |
|---|---|
| `skills/job-application/job_api.py` (incl. `api_record_applied`) | API-first job source + backend canonical applied record; called only from the verdict stage now |
| `navigation.is_auth_url` | Auth-page stop gate — unchanged, reused verbatim by `classify.py` (`auth` classification) |
| Submit confirmation gate (`confirm_checkpoint` + `--confirmed` / `--auto-apply`) | Never emits a final apply/submit without explicit human confirmation |
| SOUL.md gates (`already_applied`, `NO_APPLY` refusal, never-fill-credentials on auth) | Pre-flight and policy-level guardrails, preserved as-is |
| Existing `apply_test.py` / `helpers_test.py` / `navigation_test.py` gate tests | The confirmation, refusal, session-expiry and idempotency behaviors are contractually retained |
| `ax_tree.py` AX parse/flatten/query helpers | Reused by `classify.py` to interpret the executor's AX snapshot |

### What retires (deleted)

- `skills/job-application/portals/gupy.yaml` — selector mapping (`input[name='name']`, `button[type='submit']`, …). Selectors move out of the planner entirely.
- `skills/job-application/helpers/gupy.py` — `DEFAULT_APPLY_BUTTON_SELECTOR`, `click_apply_button`, selector-based `submit()`/`apply()`. (The InfoJobs counterpart `portals/infojobs.yaml` + `helpers/infojobs.py` is retired on the same basis; the intent contract is portal-neutral.)
- `skills/cdp-daemon/cdp_daemon.py` + `skills/cdp-daemon/cdp_daemon_test.py` — hand-rolled WS transport, replaced by the Playwright MCP `--cdp-endpoint` connection.

---

## Planner intent JSON contract

The planner's output is a single intent object. **It must not contain CSS selectors, XPaths, element ids, or per-field locators.** It carries identity, payload data, and policy — the executor derives every action from the live AX snapshot.

### Schema

| Field | Type | Required | Meaning |
|---|---|---|---|
| `intent_id` | string (uuid4) | yes | Identifies this intent/attempt; stable for retries of the same intent |
| `job_id` | string | yes | Backend/portal job slug (`ApplicationResponse.id` or URL slug) |
| `job_url` | string | yes | Canonical job URL the executor starts from |
| `portal` | string | yes | `gupy` (v1). Future: `infojobs` |
| `profile` | object | yes | Applicant data the executor fills (see example) |
| `profile.resume_path` | string | no | Absolute path to the résumé/CV file to upload |
| `policy.require_confirmation_before_final_submit` | boolean | yes | The executor MUST surface a `confirm_checkpoint` before `apply-final` unless `--confirmed`/`--auto-apply` was passed |
| `policy.never_fill_credentials` | boolean | yes | Hard gate: email/password/login fields are NEVER filled, even if visible. Cannot be overridden |
| `policy.stop_on_auth_url` | boolean | yes | Hard gate: if the current URL is an auth page (`navigation.is_auth_url`), STOP with `auth_required` |
| `policy.max_steps` | integer | yes | Step budget for the whole loop (default 25); reaching it → `INCOMPLETE` |
| `metadata` | object | no | `job_title`, `job_company`, `backend_job_id` (numeric, for `api_record_applied`), `api_base_url`, `api_token` resolution hint |

### Example

```json
{
  "intent_id": "7c2a1f4e-9b6d-4c3a-8f1e-2d5a9b0c71e3",
  "job_id": "dev-backend-jr-8472",
  "job_url": "https://jobs.gupy.io/jobs/8472",
  "portal": "gupy",
  "profile": {
    "name": "Juan Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 11 99999-0000",
    "resume_path": "/home/juan/.hermes/profiles/jobhunter-bot/resume.pdf",
    "cover_text": "Sou desenvolvedor back-end júnior..."
  },
  "policy": {
    "require_confirmation_before_final_submit": true,
    "never_fill_credentials": true,
    "stop_on_auth_url": true,
    "max_steps": 25
  },
  "metadata": {
    "job_title": "Back-end Developer Jr",
    "job_company": "ACME Tech",
    "backend_job_id": 7,
    "api_base_url": "http://localhost:8080"
  }
}
```

`--dry-run` adds an execution hint outside the intent: `"dry_run": true` (never written to `applications/`, never calls the backend). `--confirmed`/`--auto-apply` flip `policy.require_confirmation_before_final_submit` to `false` — the only policy the CLI may change.

---

## Executor state machine

The executor is the Hermes bot driving the **Playwright MCP** server (`mcp_servers` config) connected via `--cdp-endpoint http://localhost:9222` to the persistent logged-in Chromium profile (issue #39). The bot runs this loop per intent:

```
intent -> observe(AX + URL) -> classify -> act -> (budget check) -> observe -> ... 
                           \_____________ verify after apply-final → verdict _____________/
```

### Loop pseudocode

```
steps = 0
page = "start"
stall_signature = None
stall_count = 0

while page not in ("sucesso", "auth"):
    steps += 1
    if steps > policy.max_steps:            verdict = INCOMPLETE(budget_exceeded); break
    ax, url = observe()                     # Playwright MCP AX snapshot + current URL
    page, signals = classify(url, ax)       # pure classifier (classify.py)

    if page == "auth":                      # stop_on_auth_url + never_fill_credentials
        verdict = auth_required; break
    if page in ("erro",):
        if not recover(signals, ax):        # budget check on next iteration
            verdict = INCOMPLETE(error_unresolved); break

    sig = (normalize_url(url), page, top_signals(ax))
    stall_count = stall_count + 1 if sig == stall_signature else 0
    stall_signature = sig
    if stall_count >= STALL_LIMIT:          # no state progress (replaces MAX_VISITS=3)
        verdict = INCOMPLETE(loop_stalled); break

    act(page, signals, policy, profile)     # fill / next / upload / apply-final

if page == "sucesso":
    shot = save_screenshot(post_submit=True)
    verdict = evaluate_submit(url, ax, shot)    # verdict.py
```

### Classification table

| Page | Observable signals (AX snapshot + URL) | Executor action | Allowed next |
|---|---|---|---|
| `start` | Job detail page; button/heading with "Candidatar-se" / "Candidatar" / "Apply" | `next` (enter the application flow) | `form`, `triagem`, `review`, `auth`, `erro` |
| `form` | Editable fields (textbox, combobox, file input) + a submit/continue button, **no login fields dominating** | `fill` profile fields + `upload` (resume) → `next` | `triagem`, `review`, `form` (re-fill), `auth`, `erro` |
| `triagem` | Screening questions: radio sets, checkboxes, selects (e.g. "Qual sua pretensão salarial?") | `fill` (selection) → `next` ("Enviar" / "Próximo" / "Continuar") | `review`, `triagem`, `form`, `auth`, `erro` |
| `review` | Summary of applicant data + a final apply button ("Enviar candidatura" / "Candidatar") | `confirm_checkpoint` (unless confirmed) → `apply-final` | `sucesso`, `review`, `auth`, `erro` |
| `sucesso` | Success text ("Inscrição realizada", "Candidatura enviada", "Você se candidatou") or success URL | `observe` + post-submit screenshot → verdict `SUBMIT_OK` | **terminal** |
| `auth` | URL segment `login`/`auth`/`signin`/`sign-in` (`navigation.is_auth_url`) or email+password fields | **STOP** → `auth_required` with `manual_url` + screenshot | **terminal** (failure) |
| `erro` | Validation error/alert (e.g. "Preencha todos os campos") | retry within budget (`fill`/`next`), else `INCOMPLETE` | any non-terminal, or **terminal** (failure) |

### Loop guard (replaces `navigation.MAX_VISITS=3`)

- **Budget:** `policy.max_steps` (25) bounds the whole loop. Exhausted → `INCOMPLETE(budget_exceeded)`.
- **Stall:** after `STALL_LIMIT` consecutive turns with the same `(normalized URL, page classification, top signals)` — i.e. **no state progress** — the loop aborts with `INCOMPLETE(loop_stalled)`. `STALL_LIMIT = 3`.
- `navigation.is_auth_url` is reused verbatim; `NavigationGuard`/`MAX_VISITS` URL-visit counting is retired for the apply loop (kept only in legacy verify-session paths where still referenced).

---

## Verdict rules

The **verdict stage** (`verdict.py`) is the single point of truth. It runs after the loop reaches `sucesso` (or after any terminal failure) and decides what gets persisted.

### What counts as `SUBMIT_OK` evidence

`SUBMIT_OK` requires **both**:

1. **Success signal** on the post-`apply-final` page:
   - **success text** — the AX snapshot contains a substring match (case-insensitive) of one of the configured success phrases:
     `Inscrição realizada`, `Inscrição concluída`, `Candidatura enviada`, `Candidatura realizada`, `Candidatura finalizada`, `Você se candidatou`, `Aplicação enviada`, `Application submitted`, `You have applied`; **or**
   - **success URL** — a path segment of the final URL matches one of `sucesso`, `success`, `confirmacao`, `obrigado`, `applied`.
2. **Post-submit screenshot** — captured after `apply-final` and saved to `<memory>/screenshots/<job_id>-<ts>.png`.

A plain `apply-final` click with **no** success signal is `SUBMIT_DONE_NO_EVIDENCE`: never recorded as applied; the post-submit screenshot + attempt record are kept for human review.

### Outcome decision table

| Verdict / outcome | Writes `applications/<job_id>.json`? | Calls `api_record_applied`? | Writes attempt record? | Blocks future retry? |
|---|---|---|---|---|
| `SUBMIT_OK` (evidence) | **yes** | **yes** | yes (final trace) | yes (`already_applied` pre-flight) |
| `SUBMIT_DONE_NO_EVIDENCE` | no | no | yes | no |
| `INCOMPLETE` (`budget_exceeded`) | no | no | yes | no |
| `INCOMPLETE` (`loop_stalled`) | no | no | yes | no |
| `INCOMPLETE` (`error_unresolved`) | no | no | yes | no |
| `auth_required` | no | no | yes | no |
| `confirm_declined` (human said no) | no | no | yes | no |
| `error` (executor/transport, MCP unavailable) | no | no | yes | no |

**Only `SUBMIT_OK` writes the applied record. Every other outcome leaves `applications/<job_id>.json` untouched, so the job remains retryable.**

`api_record_applied` keeps its failure semantics (best-effort, `backend_record: {ok: false}` never fails the local verdict). It is invoked exclusively from the verdict stage on `SUBMIT_OK`.

---

## Attempt vs applied record formats

Both live under the bot memory dir (default `~/.hermes/profiles/jobhunter-bot/memails`).

### Applied record — `applications/<job_id>.json`

Same base schema as today (backward compatible with `check_idempotency`, which only inspects `status`), plus `evidence` + `verdict`. **`applied_at` is the verdict timestamp** (post-submit), never the plan time.

```json
{
  "job_id": "dev-backend-jr-8472",
  "contact_email": "juan@example.com",
  "portal": "gupy",
  "applied_at": "2026-09-10T14:22:01.000+00:00",
  "screenshot_path": "<memory>/screenshots/dev-backend-jr-8472-20260910T1422.png",
  "status": "applied",
  "verdict": "SUBMIT_OK",
  "evidence": {
    "method": "success_text",
    "match": "Inscrição realizada",
    "final_url": "https://jobs.gupy.io/candidaturas/confirmacao/8472",
    "screenshot": "<memory>/screenshots/dev-backend-jr-8472-20260910T1422.png"
  }
}
```

### Attempt record — `attempts/<intent_id>/<ts>.json`

Written for **every** run, whatever the outcome (one file per attempt, timestamped ISO-8601 file-safe). This is where diagnostics and loop traces live.

```json
{
  "attempt_id": "7c2a1f4e-9b6d-4c3a-8f1e-2d5a9b0c71e3",
  "job_id": "dev-backend-jr-8472",
  "job_url": "https://jobs.gupy.io/jobs/8472",
  "portal": "gupy",
  "started_at": "2026-09-10T14:19:02.000+00:00",
  "ended_at": "2026-09-10T14:22:01.000+00:00",
  "outcome": "INCOMPLETE",
  "reason": "budget_exceeded",
  "final_page": {
    "url": "https://jobs.gupy.io/jobs/8472/triagem/3",
    "page": "triagem",
    "signals": ["radio", "select", "next_button"]
  },
  "trace": [
    {"seq": 1, "page": "start", "action": "next",       "ok": true},
    {"seq": 2, "page": "form",   "action": "fill",       "ok": true},
    {"seq": 3, "page": "triagem","action": "fill",       "ok": true}
  ],
  "verdict": {"submitted": false, "evidence": null, "detail": "max_steps reached at step 25"}
}
```

Outcomes `SUBMIT_OK` write **both** files (attempt as the final trace, applied as the canonical record). All other outcomes write **only** the attempt record.

---

## Files to add / change / delete

### Add

| File | Purpose |
|---|---|
| `docs/specs/mcp-apply-loop.md` | This spec |
| `skills/job-application/intent.py` | Planner intent builder (`build_intent(...) -> Dict`, selector-free); validates profile + policy; reuses the existing input-validation and gate code paths |
| `skills/job-application/classify.py` | Pure page classifier `classify_page(url, ax_nodes) -> {page, signals}` using `navigation.is_auth_url` + `ax_tree` node filtering + the success phrase/segment lists |
| `skills/job-application/verdict.py` | Pure verdict evaluator `evaluate_submit(url, ax_nodes, screenshot_path) -> {verdict, evidence}`; `write_attempt_record(...)`; the **only** `write_applied_record(...)` and the only invocation of `job_api.api_record_applied` |
| `skills/job-application/classify_test.py` | Classifier unit tests |
| `skills/job-application/verdict_test.py` | Verdict unit tests (the new test plan below) |

### Change

| File | Change |
|---|---|
| `skills/job-application/apply.py` | Emit intent JSON instead of the selector-based action plan; **remove** the plan-time record block (~lines 1028-1033) — recording now belongs exclusively to the verdict stage; drop the `build_action_plan` fill/submit/selector model and the `helpers/` portal-flow emission; remove the cdp_daemon delegation block (`--daemon-url`, daemon submit); keep `--confirmed` / `--auto-apply` / `--dry-run` / `--record-applied` (repurposed as *verdict-input flags* for the MCP executor) and all pre-flight gates (`already_applied`, `NO_APPLY`, session expiry) |
| `skills/job-application/SKILL.md` | Document the intent contract, the MCP executor loop, verdict-only recording, retired selectors; update the "Commands" and record-back sections |
| `bot-profile/SOUL.md` | Keep all gates; replace "record after plan / post-submit via `--record-applied`" semantics with **verdict-only recording**; document the Playwright MCP executor and the rule that no record is written without success evidence; update the skill index row for `cdp-daemon` |

### Delete (retire)

| File | Reason |
|---|---|
| `skills/job-application/portals/gupy.yaml` | Selector mapping — planner no longer emits selectors |
| `skills/job-application/portals/infojobs.yaml` | Same basis (intent is portal-neutral; InfoJobs can reuse the loop later) |
| `skills/job-application/helpers/gupy.py` | `DEFAULT_APPLY_BUTTON_SELECTOR`, `click_apply_button`, selector `submit()`/`apply()` — replaced by classifier-driven actions |
| `skills/job-application/helpers/infojobs.py` | Same basis |
| `skills/cdp-daemon/cdp_daemon.py` + `skills/cdp-daemon/cdp_daemon_test.py` | Hand-rolled WS transport — replaced by Playwright MCP `--cdp-endpoint` |

### Keep (unchanged)

- `skills/job-application/job_api.py` — all functions, incl. `api_record_applied` (caller moves to verdict.py)
- `skills/job-application/navigation.py` — `is_auth_url`, `verify_session`; `NavigationGuard`/`MAX_VISITS` kept for legacy verify-session call sites, unused by the apply loop
- `skills/job-application/ax_tree.py` — AX parse/flatten/query helpers reused by `classify.py`
- `skills/job-application/apply_test.py`, `helpers_test.py`, `navigation_test.py` — gate behavior tests stay green; renamed/removed entry points get new tests in the new files

---

## Test plan

### New: `skills/job-application/verdict_test.py`

Plain JUnit-style Python unit tests (no browser, no MCP; the executor loop is not unit-tested here). Uses a temp memory dir.

| # | Test method | Verifies |
|---|---|---|
| 1 | `test_record_only_on_verified_evidence` | `SUBMIT_OK` with success text + screenshot → `applications/<job_id>.json` written with `status: applied` **and** `api_record_applied` invoked; success-URL method alone is also sufficient |
| 2 | `test_submit_done_no_evidence_not_recorded` | `apply-final` with no success text/URL → no applied file, no backend call; attempt record keeps the post-submit screenshot path |
| 3 | `test_incomplete_never_blocks_retry` | Outcome `INCOMPLETE(budget_exceeded)` → attempt record written; `applications/<job_id>.json` absent → `check_idempotency` returns `None` → a second run is planned |
| 4 | `test_auth_stop_never_fills_credentials` | Page classified `auth` (`is_auth_url` on URL, or login fields in AX) → verdict `auth_required`; the fill/upload actions are absent from the trace; no applied record; attempt record carries `manual_url` + `screenshot_path`; `policy.never_fill_credentials=true` cannot be overridden by `--auto-apply` |
| 5 | `test_confirm_gate_blocks_final_submit` | `policy.require_confirmation_before_final_submit=true` → executor emits `confirm_checkpoint` before `apply-final`; a loop trace reaching `review` stops for confirmation; `--confirmed`/`--auto-apply` flips the policy flag; `NO_APPLY`, `already_applied` still short-circuit before any action |
| 6 | `test_loop_stalled_writes_attempt_only` | Same `(url, page, signals)` for `STALL_LIMIT` turns → `INCOMPLETE(loop_stalled)`, attempt-only, retryable |
| 7 | `test_budget_exceeded_writes_attempt_only` | Steps > `policy.max_steps` → `INCOMPLETE(budget_exceeded)`, attempt-only, retryable |
| 8 | `test_applied_record_format_and_evidence` | Exact applied record shape: base fields + `verdict: SUBMIT_OK` + `evidence{method, match, final_url, screenshot}`; `applied_at` equals verdict time (post-submit), not plan time |

### New: `skills/job-application/classify_test.py`

| # | Test method | Verifies |
|---|---|---|
| 1 | `test_form_vs_triagem_vs_review_vs_sucesso` | Synthetic AX node lists classify to the right page; multi-step Gupy page sequence is recognized in order |
| 2 | `test_success_text_matching_is_case_insensitive` | "inscrição REALIZADA" matches the `sucesso` phrase list |
| 3 | `test_success_url_segment_detected` | Final URL `/candidaturas/confirmacao/8472` → `sucesso` |
| 4 | `test_auth_url_never_classified_as_form` | `is_auth_url` segment in URL → `auth`, even with visible input fields |
| 5 | `test_error_and_validation_detected` | "Preencha todos os campos" alert → `erro` |

### Kept green (existing suites)

- `apply_test.py` — `no_apply`, `already_applied`, confirm gate, session expiry, dry-run never records, backend best-effort
- `navigation_test.py` — `is_auth_url`, `verify_session` (the `NavigationGuard`/`MAX_VISITS` loop tests keep passing in legacy mode)
- `helpers_test.py` — gate/behavior tests retained for the code paths they cover; selector-literal assertions are removed together with `helpers/gupy.py`

---

## Rollout — dry-run pilot on the 19 unfinished Gupy applications

Sequenced, human-supervised rollout. Gated behind the existing default branch discipline (`dev`), one PR per phase.

### Phase 0 — inventory and reconcile

- List all Gupy jobs with an `applications/<job_id>.json` record carrying **no `evidence` field** — those were written by the legacy plan-time path (apply.py:1028-1033) and are **suspect**.
- Quarantine each suspect record to `attempts/<intent_id>/<ts>.json` with `outcome: UNVERIFIED`, `reason: legacy_plan_time_record`, then delete it from `applications/`.
- The 19 unfinished Gupy applications (jobs fetched from the API, never verified-submitted) are the pilot set. They must have **no** applied record after reconciliation, so `already_applied` cannot block them.

### Phase 1 — dry-run observe (classifier only)

- Run the MCP executor against all 19 intents with `require_confirmation_before_final_submit=true`, `never_fill_credentials=true`, `stop_on_auth_url=true`.
- Executor performs `observe` only: no fill, no `next`, no `apply-final`. Every run writes an attempt record with the full page-URL trace.
- **Gate:** dry-run writes nothing to `applications/` and calls nothing on the backend.

### Phase 2 — classifier review

- Compare the classified page sequence for each of the 19 jobs against manual inspection of the job pages.
- Tune `classify.py` phrase/signal lists until there are zero `auth`/`erro`/`form` misclassifications across the pilot set.
- Re-run Phase 1 until clean.

### Phase 3 — confirmation-gated rehearsal

- Unlock `fill`, `next`, `upload` — but **stop at `confirm_checkpoint`**.
- Verify multi-step same-URL Gupy flows (forms → triagem → review) complete without tripping the loop guard (old `MAX_VISITS=3` abort must not fire).
- No `apply-final`, no applied records.

### Phase 4 — supervised go-live

- With the human present and using `--confirmed` per job, run the 19 jobs through `apply-final`.
- For each: confirm the `SUBMIT_OK` verdict against the real Gupy success page; only evidenced successes get recorded.
- Track outcomes across the 19: `SUBMIT_OK`, `SUBMIT_DONE_NO_EVIDENCE`, `INCOMPLETE`, `auth_required`.

### Pilot success criteria

- **0** false applied records (every recorded application has a post-submit screenshot + success evidence).
- All 19 previously unfinished jobs either reach `SUBMIT_OK` or remain retryable (attempt record only) with a documented reason.
- `navigation.is_auth_url` stop, `never_fill_credentials`, and the confirmation gate fired correctly in every run.

---

## Expected behavior

### Scenario 1: Happy path — multi-step Gupy flow completes
- **GIVEN** `apply.py` emits intent for a Gupy job whose flow is detail → form → triagem → review → final apply, all on the same host URL, and the browser is logged in
- **WHEN** the executor runs observe → classify → act and reaches `sucesso` after `apply-final`
- **THEN** the verdict stage writes `applications/<job_id>.json` with `status: applied` + success evidence, saves the post-submit screenshot, and calls `api_record_applied`
- **AND** the same-URL multi-step navigation does **not** trip the old loop guard; `applied_at` is the verdict time

### Scenario 2: Budget exhausted mid-flow — retry stays possible
- **GIVEN** a Gupy flow that needs more than `policy.max_steps` turns (or stalls with no state progress for `STALL_LIMIT` turns)
- **WHEN** the executor aborts with `INCOMPLETE`
- **THEN** only an attempt record is written under `attempts/<intent_id>/<ts>.json`
- **AND** `applications/<job_id>.json` does not exist, so a future run passes the `already_applied` pre-flight

### Scenario 3: Auth page encountered — hard stop
- **GIVEN** the browser lands on a path segment `login`/`auth`/`signin`/`sign-in` or a page dominated by email/password fields
- **WHEN** the loop classifies `auth`
- **THEN** the loop stops with `auth_required`, no credential field is ever filled, no applied record is written
- **AND** the attempt record carries `manual_url` + `screenshot_path` for the human

### Scenario 4: Confirmation gate blocks the final submit
- **GIVEN** `policy.require_confirmation_before_final_submit=true` and the flow reaches `review`
- **WHEN** the executor would click `apply-final`
- **THEN** the executor emits a `confirm_checkpoint` and waits — no final submit without `--confirmed`/`--auto-apply`
- **AND** `NO_APPLY` and `already_applied` still short-circuit before any action

### Scenario 5: Final click without proof is never recorded
- **GIVEN** the executor clicks `apply-final` but the next page shows no success text and no success URL segment
- **WHEN** the verdict stage evaluates the evidence
- **THEN** the outcome is `SUBMIT_DONE_NO_EVIDENCE`: no applied record, no backend call, attempt record retains the post-submit screenshot for manual review

---

## Business rules

1. **Verdict-only recording** — `applications/<job_id>.json` is written and `api_record_applied` is called **exclusively** by the verdict stage on `SUBMIT_OK`. Never at plan time (removes apply.py:1028-1033).
2. **Selector-free intent** — the planner emits no CSS selectors/XPaths; every DOM interaction is derived by the executor from the AX snapshot classification.
3. **Evidence is mandatory** — `SUBMIT_OK` requires a success text or success URL **and** a post-submit screenshot; anything less is `SUBMIT_DONE_NO_EVIDENCE` and is never recorded as applied.
4. **Retryability** — every non-`SUBMIT_OK` outcome writes only an attempt record and never blocks a future attempt (`already_applied` pre-flight reads only `applications/`).
5. **Hard policy gates** — `never_fill_credentials` and `stop_on_auth_url` cannot be disabled by `--confirmed`/`--auto-apply`; `stop_on_auth_url` reuses `navigation.is_auth_url`.
6. **Confirmation gate** — no `apply-final` without explicit confirmation; only the planner may flip `require_confirmation_before_final_submit` (via `--confirmed`/`--auto-apply`).
7. **Loop termination** — the step budget (`max_steps`, default 25) and progressive-state stall detection (`STALL_LIMIT=3`) replace `navigation.MAX_VISITS=3` for the apply loop; legit same-URL multi-step flows are no longer aborted.
8. **Dry-run** — never writes to `applications/`, never touches the backend, and requires no confirmation.
9. **Backend best-effort** — `api_record_applied` failure warns (`backend_record: {ok: false}`) and never fails the local verdict or changes exit code.

---

## Error cases

| Situation | Outcome / code | Expected behavior |
|---|---|---|
| Current URL is an auth page or login fields dominate | `auth_required` | Stop; `manual_url` + `screenshot_path` surfaced; attempt record; no credentials filled |
| Steps exceed `policy.max_steps` | `INCOMPLETE` (`budget_exceeded`) | Attempt record; retry allowed |
| Same `(url, page, signals)` for `STALL_LIMIT` turns | `INCOMPLETE` (`loop_stalled`) | Attempt record; retry allowed |
| `erro` page not recoverable within budget | `INCOMPLETE` (`error_unresolved`) | Attempt record with error signals; retry allowed |
| `apply-final` clicked, no success evidence | `SUBMIT_DONE_NO_EVIDENCE` | No applied record; post-submit screenshot + attempt record retained |
| Executor/transport failure (MCP unreachable, `--cdp-endpoint` down) | `error` | Attempt record; clear machine-readable detail; retry allowed |
| Profile marked `NO_APPLY` (pre-flight) | `no_apply_refusal` | No attempt started; existing gate |
| `applications/<job_id>.json` exists with `status: applied` (pre-flight) | `already_applied` | No attempt started; existing gate |
| Backend POST `api_record_applied` fails | `backend_record: {ok: false, error}` | Local verdict unaffected; exit code unchanged; best-effort warn |

---

## Acceptance criteria

| # | Criterion | Test method |
|---|-----------|-------------|
| 1 | Applied record written **only** on `SUBMIT_OK` with success evidence | `test_record_only_on_verified_evidence`, `test_submit_done_no_evidence_not_recorded` |
| 2 | Non-`SUBMIT_OK` outcomes never write `applications/<job_id>.json` and never block retry | `test_incomplete_never_blocks_retry`, `test_loop_stalled_writes_attempt_only`, `test_budget_exceeded_writes_attempt_only` |
| 3 | `auth` pages stop the loop; credentials never filled; `never_fill_credentials` non-overridable | `test_auth_stop_never_fills_credentials` |
| 4 | Confirmation gate fires before `apply-final`; `already_applied` and `NO_APPLY` still short-circuit | `test_confirm_gate_blocks_final_submit` |
| 5 | Planner intent is selector-free and carries the policy block | `intent_test.py` / CLI emits `policy` keys |
| 6 | Classifier maps the four Gupy page kinds + `auth`/`erro` + success evidence | `classify_test.py` (all cases) |
| 7 | Rollout pilot: 19 unfinished jobs processed with 0 false applied records and all non-submissions retryable | Phase 0–4 reports |

---

## Out of scope

- **Official Gupy API** — `api.gupy.io` is recruiter-only; building a reverse-engineered API client is explicitly out of scope.
- **InfoJobs and LinkedIn apply loops** — v1 targets Gupy only. The intent contract is portal-neutral so InfoJobs can adopt the loop later, but no InfoJobs/LinkedIn executor work ships in this spec.
- **Credentials / SSO / CAPTCHA** — no credential autofill (hard gate), no CAPTCHA solving, no SSO automation.
- **Selector generation or maintenance** — the project stops shipping per-portal CSS selectors; the executor is fully vision/AX-driven.
- **Browser-session persistence** beyond the existing issue #39 persistent Chromium profile (no login-state serialization, no multi-account).
- **Backend changes** — the `POST /api/jobs/{id}/applied` contract and the `applications/` record schema stay compatible; only the bot-side *writes* it later (verdict stage).
- **Real-time progress reporting / UI** — no new endpoints, no TUI/API surface changes.
- **cdp-daemon replacement for other skills** — the daemon is retired for the apply loop; `job-portal-browser`'s transport is not part of this spec.

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/mcp-apply-loop.md.

Step 1 — write skills/job-application/verdict_test.py and
skills/job-application/classify_test.py covering all scenarios and the
acceptance-criteria table in this spec. The tests must fail (RED).
Do not write the implementation yet.

Step 2 — wait for confirmation before implementing intent.py, classify.py,
verdict.py and the apply.py rewiring.
```