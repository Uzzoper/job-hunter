# Spec: Generic External Portal Apply (`external` portal over the MCP executor)

> **Layer:** `skills/job-application` (planner / classifier / verdict — pure Python,
> stdlib-only) + `bot-profile/SOUL.md` docs
> **Depends on:** `mcp-apply-loop.md` (observe → classify → act → verify loop,
> verdict-only recording, evidence rules), `deterministic-apply-runbook.md`
> (preflight, phase-gated rollout, fail-closed), `job-application-auth-loop.md`
> (`is_auth_url`), `answer-policy.md` (grounded answers), `resume-upload.md`
> (resume file source)
> **Amends:** the "LinkedIn is manual by design" decision
> (`skills/job-application/SKILL.md` lines 163/655, `deterministic-apply-runbook.md`
> §8) — guest-page navigation is unlocked **for link resolution only**
> **No backend changes** — `POST /api/jobs/{id}/applied` (JobController:149)
> accepts the verdict call unchanged

---

## Goal

Extend the existing planner-intent + MCP-executor apply loop with a new portal,
`external`, that completes applications on **any third-party site reached from a
job listing** — ATS boards (Greenhouse / Lever / Ashby), Workday-class career
portals, and corporate career pages — including listings **fetched from
LinkedIn that lead to an external form**.

Explicitly **not** in scope: LinkedIn Easy Apply (stays manual), logging into
LinkedIn, acting on LinkedIn beyond opening a guest job page and following its
external apply link.

The v1/Gupy loop (`mcp-apply-loop.md`) stays untouched: same state machine,
same gates, same verdict rules — `external` is a portal value, not a new
architecture.

---

## Context

The operator clarified the automation target: the useful volume is **jobs whose
"Apply" leaves LinkedIn** for a company-run form. LinkedIn Easy Apply was
evaluated and intentionally skipped (highest ban-detection exposure, and the
repo deliberately keeps it manual). External pages carry **no LinkedIn-account
risk** when reached as a guest or via ATS-direct URLs; the residual risk moves
to the generic-form problem — unknown UIs, captcha walls, and multi-step
profiles (Workday) that are login-gated.

Decision (operator-approved): unlock a **generic external portal** in the
existing loop rather than adding a second agent runtime (a Browser Use sidecar
was considered and rejected — it would duplicate the executor, gates, and
verdict, and reintroduce the free-form agent drift already corrected by
`deterministic-apply-runbook.md`). The motor remains the **Hermes jobhunter-bot
profile** driving Playwright MCP via `--cdp-endpoint` — unchanged.

### What the site landscape looks like

| Entry kind | Example | Notes |
|---|---|---|
| ATS board (direct) | `boards.greenhouse.io/acme/jobs/123`, `jobs.lever.co/acme/…`, `jobs.ashbyhq.com/acme/…` | `job.url` is already the apply page; form is standard (name/email/phone/resume/questions) |
| LinkedIn guest → external | `linkedin.com/jobs/view/<id>` shows an external apply link (often a Greenhouse/Lever iframe or a redirect URL) | No login needed on LinkedIn guest pages; may hit `linkedin.com/authwall` — treated as auth-stop |
| Career portal | `acme.wd3.myworkdayjobs.com/…`, `acme.com/careers/job/…` | Multi-step, sometimes login-gated; higher variance |

---

## Expected behavior (new/changed only; inherited behavior keeps its spec text)

### Portal allow-list (`intent.py`)

#### Scenario 1: `external` is accepted
- **GIVEN** `apply.py --portal external --job-url <listing-url> --profile …`
- **WHEN** the pre-flight gates run
- **THEN** `SUPPORTED_PORTALS == ("gupy", "infojobs", "external")` and the intent
  is emitted with `portal: "external"`
- **AND** unknown portals and case variants keep erroring `unknown_portal` (exit 1)

#### Scenario 2: external intent requires a backend-resolved job
- **GIVEN** portal `external`
- **WHEN** the intent is built
- **THEN** `metadata.backend_job_id` (numeric) is mandatory — the planner
  refuses with `invalid_job_url`-style JSON error (exit 1) when the job came
  from hand-typed input, because idempotency for external sites is keyed on the
  backend job id, not on the arbitrary site URL
- **AND** the Gupy-specific urlslug integrity checks stay Gupy-scoped and do
  not reject external hosts

### Job-id derivation (`apply.py`)

#### Scenario 3: idempotency key for external
- **GIVEN** portal `external` and a backend job loaded via `--from-api`/`--job-id`
- **WHEN** `derive_job_id` resolves for the intent
- **THEN** for external the slug is the **backend numeric id** (string form),
  not the URL path — LinkedIn `/jobs/view/<id>` and career URLs with arbitrary
  segments do not match the Gupy `/jobs/<slug>/` pattern by construction
- **AND** `already_applied` idempotency continues to read
  `applications/<job_id>.json` unchanged

### Auth stop, extended (`navigation.py` + `classify.py`)

#### Scenario 4: LinkedIn authwall counts as auth
- **GIVEN** the browser lands on a URL whose path contains `/authwall`
- **WHEN** `is_auth_url(url)` runs
- **THEN** it returns `True` (`/authwall` joins `/login`, `/auth`, `/signin`,
  `/candidates/auth`)
- **AND** `never_fill_credentials` and the auth hard-stop remain
  non-overridable

### Page classification (`classify.py`)

#### Scenario 5: `start` covers every external entry kind
- **GIVEN** the browser opens a LinkedIn guest job page, an ATS board job page,
  or a corporate career listing
- **WHEN** the page is classified
- **THEN** a page with an external apply affordance (button/link/iframe with
  "Apply" / "Candidatar-se" / "Inscrever-se" / "Apply here") classifies `start`
  and the executor follows it (`next`), including cross-origin iframes (the AX
  snapshot is taken of the frame the form lives in)
- **AND** a listing page **without** an apply affordance classifies
  `erro` (external_apply_missing) and exits `INCOMPLETE` — an attempt record is
  written, no applied record, retry allowed

#### Scenario 6: success evidence for external sites
- **GIVEN** the executor clicked the final submit on an external form
- **WHEN** the verdict evaluates evidence
- **THEN** `SUBMIT_OK` requires (unchanged base rule) success text **or**
  success URL **plus** a post-submit screenshot, with the phrase/segment lists
  extended by: `Thank you for applying`, `Your application has been`,
  `application was successfully`, `We received your application`,
  `Obrigado por se candidatar`, `Candidatura recebida`, path segments
  `thank-you`, `applied`, `confirmation`
- **AND** anything less stays `SUBMIT_DONE_NO_EVIDENCE`: never recorded,
  screenshot retained for review

### Answers on external forms (`answer.py`) — reuse, unchanged

- Questions are answered **only** from grounded profile facts
  (`answer-policy.md`); an unknown/optional question is left blank and flagged
  in the attempt record; the answer must never be model-invented
- For **application-status or eligibility questions** marked required with no
  grounded answer (e.g. work authorization), the run stops with
  `INCOMPLETE(answer_required)` instead of guessing

### Preflight (`preflight.py`) — portal-aware

#### Scenario 7: session check is Gupy-scoped
- **GIVEN** portal `external` and the default-on preflight config
- **WHEN** `preflight.evaluate` runs
- **THEN** checks 1–3 (Chromium process, CDP `/json` answer, tool attached to
  the **same** CDP endpoint) apply as-is
- **AND** check 4 (Gupy session marker) is skipped for external — no persistent
  portal session is assumed
- **AND** a new check 5 (external daily cap) applies for external only
  (Scenario 8)

#### Scenario 8: daily external cap
- **GIVEN** `preflight-config.json` carries `"external_apply": {"daily_cap": 10}`
  (default 10 when absent)
- **WHEN** preflight runs for an external intent
- **THEN** it counts today's attempt records under `attempts/` (attempt
  `started_at` = today, job through the external executor); count ≥ cap → fail
  with a machine-readable error (`external_daily_cap_reached`) and no intent

### Gate order (`apply.py`)

Unchanged order — `external` only plugs into the portal allow-list, profile
validation, browser/session preflight, idempotency, then intent emission. The
confirmation gate (`confirm_checkpoint` before final submit unless
`--confirmed`) and dry-run semantics are inherited exactly:
**dry-run writes nothing to `applications/` and calls nothing on the backend.**

---

## Executor state machine (unchanged, parameterized)

The loop, budget, stall guard, and verdict rules of `mcp-apply-loop.md` apply
verbatim. Parameters for `external`:

| Knob | Gupy default | External default | Where |
|---|---|---|---|
| `policy.max_steps` | 25 | 30 | external flows are longer (Workday multi-step) |
| `STALL_LIMIT` | 3 | 3 | state-progress contract is portal-neutral |
| confirmation gate | required | required | non-negotiable in both |
| verdict evidence | required | required | phrase/segment lists extended |

---

## Files to change

| File | Change |
|---|---|
| `skills/job-application/intent.py` | Add `"external"` to `SUPPORTED_PORTALS`; external branch in `build_intent` requiring `metadata.backend_job_id`; `policy.max_steps` default 30 for external |
| `skills/job-application/apply.py` | Accept `--portal external`; external-aware `derive_job_id` (backend id key; hand-typed refusal); error text `(v3 supports: gupy, infojobs, external)` |
| `skills/job-application/navigation.py` | Add `/authwall` to auth-path patterns + test |
| `skills/job-application/classify.py` | `start` affordance set (+ "Apply here", iframe handling note); extended success phrase/segment lists; `external_apply_missing` / `answer_required` incomplete reasons |
| `skills/job-application/preflight.py` | Portal-aware check 4 (Gupy-only) + new check 5 external daily cap reading `preflight-config.json` |
| `skills/job-application/SKILL.md` | New portal row; replace both "LinkedIn is manual by design / NOT supported" passages with the amended decision: **guest-page navigation is allowed for link resolution only — no LinkedIn login, no Easy Apply, no action on LinkedIn itself; Easy Apply stays manual**; add Workday/msg-if-`authwall` guidance; document the daily cap |
| `bot-profile/SOUL.md` | Mirror the amended LinkedIn decision + the external gates in standing instructions |
| Tests | `intent_test.py`, `apply_test.py`, `classify_test.py`, `navigation_test.py`, `preflight_test.py`, `verdict_test.py` — new cases per the test plan below; all existing cases stay green |

**No backend change**: `api_record_applied` posts `job_id` to
`POST /api/jobs/{id}/applied`, which carries no portal field — verified before
GREEN.

---

## Test plan (TDD — RED before implementation)

Plain `unittest`, no browser, executor loop not unit-tested (same policy as
`mcp-apply-loop.md`).

### `intent_test.py`
| Test | Verifies |
|---|---|
| `test_external_portal_allowed` | `is_supported_portal("external")` → True; build_intent emits `portal: external` |
| `test_external_requires_backend_job_id` | build_intent without `metadata.backend_job_id` for external → the planner error, no intent |
| `test_external_max_steps_default_30` | policy carries `max_steps: 30` for external when unset |
| `test_unsupported_portal_still_errors` | `linkedin`, `workday`, `GUPY` → `unknown_portal` still |

### `apply_test.py`
| Test | Verifies |
|---|---|
| `test_external_hand_typed_url_refused` | external + hand-typed URL (no API source) → exit 1 JSON error, nothing written |
| `test_external_slug_is_backend_id` | derive step for external returns the backend numeric id string |
| `test_external_gate_order_unchanged` | refusal/NO_APPLY/already_applied still short-circuit before any intent |

### `navigation_test.py`
| Test | Verifies |
|---|---|
| `test_authwall_is_auth_url` | `https://www.linkedin.com/authwall/…` → True |

### `classify_test.py`
| Test | Verifies |
|---|---|
| `test_external_start_kinds` | ATS board page, LinkedIn guest page, career listing with apply affordance → `start`; without affordance → `erro`/`external_apply_missing` |
| `test_external_success_phrases` | new phrases (en/pt) match case-insensitively; URL segments `thank-you`/`applied`/`confirmation` match |
| `test_authwall_never_classified_as_form` | authwall URL with visible inputs → `auth` |

### `preflight_test.py`
| Test | Verifies |
|---|---|
| `test_external_skips_session_check` | checks 1–3 run, Gupy check 4 not required |
| `test_external_daily_cap_blocks` | cap 1 + one today attempt → fail `external_daily_cap_reached` |
| `test_external_daily_cap_default` | missing config → cap 10 |

### `verdict_test.py`
| Test | Verifies |
|---|---|
| `test_external_submit_ok_evidence` | success phrase + screenshot → applied record + `api_record_applied` |
| `test_external_no_evidence_not_recorded` | final click without success signal → attempt-only, retryable |
| `test_answer_required_incomplete` | mandatory unanswerable question → `INCOMPLETE(answer_required)`, no final submit |

---

## Rollout — phased pilot (inherits `deterministic-apply-runbook.md` §3)

- **Phase 0 — inventory**: list backend jobs plausibly external-apply (ATS
  provider origins; LinkedIn-origin jobs whose guest page shows an external
  link). Pick a pilot of ~10 (5 ATS-direct, 5 LinkedIn-led).
- **Phase 1 — dry-run observe** (no fill, no submit): full traces, no records,
  no backend calls.
- **Phase 2 — classifier review**: tune phrase/discipline lists against the
  pilot until zero `auth`/`erro` misclassifications; re-run Phase 1.
- **Phase 3 — confirmation-gated rehearsal**: `fill`/`next`/`upload` unlocked,
  stop at `confirm_checkpoint`.
- **Phase 4 — supervised go-live**: human present, `--confirmed` per job;
  verdict vs real site confirmations; daily cap enforced.

**Pilot success criteria**: 0 false "applied" records; every non-submission
retryable with a documented reason; auth gate and confirmation gate fired in
every run; no LinkedIn login state ever used.

---

## Business rules

1. **Second architecture rejected** — no new runtime, no new LLM provider; the
   Hermes profile keeps being the motor via Playwright MCP (`--cdp-endpoint`).
2. **Verdict-only recording, evidence mandatory** — unchanged; extended
   evidence lists only.
3. **Idempotency for external is backend-keyed** — `backend_job_id` is
   mandatory; hand-typed URLs are refused (lesson #82 applied by analogy).
4. **LinkedIn posture** — guest pages only, for link resolution; authwall ⇒
   auth-stop; Easy Apply and any LinkedIn submission/login stay manual
   (amends the previous "out of scope" decision, recorded here).
5. **Never fill credentials, stop on auth URL** — non-overridable hard gates,
   now with `/authwall`.
6. **Answers are grounded** — `answer-policy.md` semantics; mandatory
   unanswerable ⇒ `INCOMPLETE(answer_required)`, no guessing.
7. **Daily external cap** — planner-side preflight check, default 10/day.
8. **Fail-closed** — ambiguity stops the run; no alternate browsers, no ad-hoc
   scripts (`deterministic-apply-runbook.md` §3.4).
9. **No backend change** — `api_record_applied` and `applications/` schema
   stay compatible; portal field remains local-record-only.

---

## Error cases

| Situation | Outcome / code | Behavior |
|---|---|---|
| Hand-typed URL for external | planner JSON error, exit 1 | no intent, nothing written |
| No external apply affordance on the landing page | `INCOMPLETE(external_apply_missing)` | attempt record; retry allowed |
| Landing on `linkedin.com/authwall` or any auth path | `auth_required` | hard stop; `manual_url` + screenshot |
| Mandatory question with no grounded answer | `INCOMPLETE(answer_required)` | no final submit; attempt record |
| Daily cap reached | `external_daily_cap_reached` (preflight) | run stops before intent |
| Submit without success evidence | `SUBMIT_DONE_NO_EVIDENCE` | never recorded; screenshot kept |
| CAPTCHA (Turnstile/reCAPTCHA/hCaptcha) | stop | human solves manually; no captcha solving (unchanged) |

---

## Acceptance criteria

| # | Criterion | Test method |
|---|---|---|
| 1 | `external` accepted, others still error cleanly | `intent_test.py` |
| 2 | External idempotency keyed on backend id; hand-typed refused | `apply_test.py` |
| 3 | Authwall ⇒ auth-stop | `navigation_test.py` + `classify_test.py` |
| 4 | External success evidence extended; no-evidence never recorded | `classify_test.py` + `verdict_test.py` |
| 5 | Preflight Gupy-check skipped, daily cap enforced for external | `preflight_test.py` |
| 6 | Pilot (Phase 0–4) with 0 false applied records | rollout reports |

---

## Out of scope

- **LinkedIn Easy Apply** — manual, unchanged
- **LinkedIn login / feed actions** — never
- **CAPTCHA solving** — human-in-the-loop by design
- **Backend persistence changes** (portal column, applyUrl extraction into the
  jobs table) — possible follow-up after the pilot proves value
- **Browser Use / any second agent runtime** — deliberately rejected
  (research + drift precedent); revisiting requires a new spec
- Selector layers per external portal — the loop stays AX-driven and
  selector-free

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/external-portal-apply.md (plus mcp-apply-loop.md
and deterministic-apply-runbook.md for inherited behavior).

Step 1 — write the RED tests first (intent_test.py, apply_test.py,
navigation_test.py, classify_test.py, preflight_test.py, verdict_test.py new
cases per the test plan). Plain unittest. Do not touch implementation yet;
show the tests and wait for confirmation before implementing.
```
