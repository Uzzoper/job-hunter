# Spec: Email refusal contract (NO_APPLY → REJECTED)

> **Layer:** `application` (use case) + `domain` (status) + `infrastructure` (persistence)
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.application.service.EmailGenerationService` (`buildPrompt`, `parseEmailDraft`)
> - `com.juanperuzzo.job_hunter.domain.model.EmailStatus` (gains `REJECTED`)
> - `com.juanperuzzo.job_hunter.application.service.EmailSendingService` (blocks `REJECTED`)
> - `com.juanperuzzo.job_hunter.application.service.AutoSendEligibilityService` (never selects `REJECTED`)
> **Corresponding tests:** `EmailGenerationServiceTest.java`, `AutoSendEligibilityServiceTest.java`, `EmailSendingServiceTest.java`
> **Depends on:** `generate-email.md`, `send-email.md`, `auto-send-scheduler.md`, `prompts.md` (Prompt 2)
> **Issue:** #28 — `fix(email): prevent sending refusal/fallback draft as application`

---

## Context

On 01/09/2026 auto-send dispatched internal AI feedback as a real application
(Job 134, Draft 10 — Analista de Fidelização). The whole send chain trusts any
AI text as a valid application: prompt has no refusal contract, parser takes
the first line as subject without validation, eligibility only checks
`status + contactEmail`, and the sender forwards content verbatim.

---

## Expected behavior

### Scenario 1: AI refuses — no fit (AI path)
- **GIVEN** a valid `Job`, `JobAnalysis`, and user profile (AI path, `matchScore < minMatchScore`)
- **WHEN** `generate(userId, jobId)` is called and `AiPort.complete()` returns text starting with `NO_APPLY:` (after trim)
- **THEN** no subject/body parsing happens
- **AND** the persisted `EmailDraft` has `status = REJECTED`, `subject = ""` (or the one-line reason?), `body` = full AI response trimmed
- **AND** the draft is returned with `REJECTED` status

> Decision: `subject` stores `""` and `body` stores the full `NO_APPLY: reason` text,
> so the reason stays auditable and no fake "Subject: ..." is ever produced.
> Alternative (reason → subject) rejected: it would look like a sendable subject.

### Scenario 2: parser recognizes refusal prefix
- **GIVEN** any AI response string
- **WHEN** `parseEmailDraft()` receives it after `trim()`
- **THEN** if it starts with exactly `NO_APPLY:` (case-sensitive, uppercase) it produces a `REJECTED` draft per Scenario 1
- **AND** otherwise it follows the current `Subject: ` split logic and produces `PENDING` (unchanged)

### Scenario 3: auto-send ignores REJECTED (both modes)
- **GIVEN** drafts with `status == REJECTED` (with `contactEmail` non-null and any score)
- **WHEN** `AutoSendEligibilityService.nextEligibleDraft()` runs in full-auto (`PENDING`) or review-gate (`APPROVED`) mode
- **THEN** `REJECTED` drafts are never selected
- **AND** if only `REJECTED` drafts exist, returns `Optional.empty()` (same as "nothing eligible", debug-level log, no error)

### Scenario 4: manual send blocked on REJECTED
- **GIVEN** an `EmailDraft` with `status == REJECTED`
- **WHEN** `send(userId, jobId)` is called (explicit endpoint, CLI, or scheduler)
- **THEN** throws the new domain exception (see Interface contract) before any recipient lookup or `EmailSenderPort.send()` call
- **AND** `EmailSenderPort.send` is never called, draft stays `REJECTED`

### Scenario 5: regeneration re-evaluates (no silent resurrection, no permanent lock)
- **GIVEN** an existing `REJECTED` draft for `(job_id, user_id)`
- **WHEN** `generate(userId, jobId)` is called again
- **THEN** the new AI response decides: `NO_APPLY:` → stays `REJECTED` (same `id` updated); valid email → transitions to `PENDING` (explicit reactivation, same `id`)
- **AND** the template branch (`matchScore >= minMatchScore`) always produces `PENDING` (deterministic, never refused)

---

## Business rules

- Prompt gains a final mandatory rule (after rule 10, before tone guide):
  > `11. If the vacancy clearly has no fit with the candidate (non-tech role, stack entirely outside the candidate's, or level far below), DO NOT write an email. Respond with exactly one line: NO_APPLY: [one-line reason in English]. No subject, no body, no signature.`
- Refusal detection is prefix-only: `response.trim().startsWith("NO_APPLY:")`, case-sensitive. No fuzzy matching in v1.
- `EmailStatus` gains `REJECTED`: `PENDING, APPROVED, SENT, REJECTED`. Sits outside the sendable lifecycle: `PENDING → APPROVED → SENT` unchanged; `REJECTED` is terminal unless regenerated into `PENDING` (Scenario 5).
- Eligibility uses inclusion lists (`PENDING` / `APPROVED`) so `REJECTED` is excluded implicitly; no `status != REJECTED` query needed. No change to `collectUserBuckets()` logic required beyond the enum gain — covered by regression test.
- Persistence: `email_drafts.status` is `VARCHAR(20)` free-form (V1 baseline, no CHECK). Java maps via `EmailStatus.valueOf()`. A Flyway migration documents `REJECTED` as legal value (next free version — `V3` if still free at implementation time; `generate-email.md:58` already claims a `V3` for `(job_id, user_id)` uniqueness that does not exist in the repo, so the implementer must reconcile numbering and never edit applied V1/V2).
- No backfill of legacy drafts: existing rows are only `PENDING/APPROVED/SENT`; `valueOf()` stays safe as long as no garbage was written manually.

---

## Interface contract

```java
// Domain — one new value, nothing else changes
public enum EmailStatus { PENDING, APPROVED, SENT, REJECTED }
```

```java
// Application — signatures unchanged
public interface GenerateEmailUseCase {
    EmailDraft generate(Long userId, Long jobId); // may now return REJECTED
}
public interface SendEmailUseCase {
    EmailDraft send(Long userId, Long jobId); // throws on REJECTED
}
```

```java
// Domain — one new exception (AGENTS.md: custom domain exceptions, no stack traces in HTTP)
public class RefusedDraftException extends RuntimeException {
    public RefusedDraftException(String message) { super(message); }
}
```

`GlobalExceptionHandler` maps `RefusedDraftException` → 422 (same family as "exists but not sendable"), without stack trace. Exact status code confirmed at implementation time against the handler's current mapping.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| `send()` on `REJECTED` draft | `RefusedDraftException` (new) | fails before `EmailSenderPort.send`, draft stays `REJECTED` |
| `approve()` on `REJECTED` draft | current approve path throws (already throws for non-`PENDING`) | stays `REJECTED`, no transition |
| AI returns `NO_APPLY:` without reason text | none — still `REJECTED` | `body` = `"NO_APPLY:"`, auditable |
| Legacy `status` string unknown to enum | `IllegalArgumentException` from `valueOf()` (pre-existing) | unchanged by this spec; no migration of old rows needed |

---

## Out of scope (deliberate — issue #28 item D deferred)

- Light subject-keyword validation (`"não tem nada a ver"`, `"não tem fit"`) in `AutoSendEligibilityService` or `HermesBotEmailSender` — fuzzy, language-fragile, and diverges from `hermes-agent-integration.md` ("no free-form content enforcement"). Strict `NO_APPLY:` prefix only in v1.
- Contact blocklist (`dpo@`, `privacy@`, `legal@`) and per-domain rate limiting — separate issues (#27 family).
- Idempotency by `(job_id, contact_email)` — issue #27, independent, implemented separately.
- Platform form-filling agent — unchanged.

---

## Existing specs to update after GREEN

- `generate-email.md`: `status = PENDING` → `PENDING` or `REJECTED`; enum contract gains `REJECTED`; "Out of scope: validating AI output (trusts prompt + model)" is inverted by this spec and must be rewritten; `:58` `V3` claim must be reconciled with the real next migration number.
- `send-email.md`: "Only `PENDING` may be sent" → "`PENDING` (full-auto) or `APPROVED` (review-gate); `REJECTED` never sendable (throws `RefusedDraftException`)".
- `auto-send-scheduler.md`: Scenario 6 ("nothing eligible") explicitly includes "only `REJECTED` drafts exist".
- `prompts.md` Prompt 2: append refusal rule 11 verbatim.

---

## Acceptance criteria (from #28, refined)

- [ ] `EmailGenerationServiceTest.generate_whenNoFit_shouldReturnRejectedDraft` (AI returns `NO_APPLY: ...` → `REJECTED`, persisted, `AiPort` called once)
- [ ] `EmailDraft_parse_whenNoApplyPrefix_shouldReturnRejectedStatus` (parser unit — via `generate` with mocked `AiPort`, prefix with leading whitespace still detected; non-prefixed fallback text still `PENDING`)
- [ ] `AutoSendEligibilityServiceTest.nextEligibleDraft_whenAllRejected_shouldReturnEmpty`
- [ ] `EmailSendingServiceTest.send_whenRejected_shouldThrowAndNeverCallSender` (extra — closes the manual-send hole noted in recon)
- [ ] Flyway migration documenting `REJECTED` (next free `V*`, reconciled with the phantom `V3` claim)
- [ ] `REJECTED` never enters auto-send (both modes) nor manual `send()`
- [ ] Existing suites stay GREEN; #27 untouched and still independently valid

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/email-no-apply-refusal.md
plus docs/specs/generate-email.md, send-email.md, auto-send-scheduler.md,
and docs/specs/prompts.md (Prompt 2).

Step 1 — write the RED tests only (no production code):
- EmailGenerationServiceTest.generate_whenNoFit_shouldReturnRejectedDraft
- EmailDraft_parse_whenNoApplyPrefix_shouldReturnRejectedStatus
  (via generate with mocked AiPort: "  NO_APPLY: non-tech role" → REJECTED;
   and a fallback-feedback text without prefix → PENDING)
- AutoSendEligibilityServiceTest.nextEligibleDraft_whenAllRejected_shouldReturnEmpty
- EmailSendingServiceTest.send_whenRejected_shouldThrowAndNeverCallSender
Mock AiPort / repositories (Mockito, no Spring context). Show the tests
and ask "do you want to adjust before implementing?".

Step 2 — after confirmation, implement GREEN:
EmailStatus.REJECTED, buildPrompt rule 11, parseEmailDraft prefix branch,
EmailSendingService REJECTED guard (new RefusedDraftException + handler
mapping), Flyway migration (next free V*, reconciled numbering).
Keep collectUserBuckets() inclusion logic; cover it by regression test only.

Step 3 — after GREEN, refactor minimally and update the four existing
specs listed above. Confirm which tests are GREEN.
```
