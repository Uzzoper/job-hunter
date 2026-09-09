# Spec: Email idempotency by (job_id, recipient_email)

> **Layer:** `application` (guards) + `domain` (record) + `infrastructure` (persistence + migration)
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.domain.model.EmailDraft` (gains `recipientEmail` snapshot)
> - `com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository` (gains `findSentByJobIdAndRecipientEmail`)
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.EmailDraftEntity` (new column)
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.EmailDraftPersistenceAdapter` + `EmailDraftJpaRepository` (new finder)
> - `com.juanperuzzo.job_hunter.application.service.EmailGenerationService` (check on create)
> - `com.juanperuzzo.job_hunter.application.service.EmailSendingService` (double-check on send)
> - `com.juanperuzzo.job_hunter.application.service.ApproveDraftService` (blocks `REJECTED`)
> **Corresponding tests:** `EmailGenerationServiceTest.java`, `EmailSendingServiceTest.java`, `ApproveDraftServiceTest.java` (all service-level, mocked ports — there are no persistence/adapter tests in the repo)
> **Depends on:** `generate-email.md`, `send-email.md`, `auto-send-scheduler.md`, `email-no-apply-refusal.md`, `sqlite-local-persistence.md`
> **Issue:** #27 — `feat(email): idempotency by (job_id, contact_email) to avoid domain spam`

---

## Context

On 01/09/2026 the batch send fired 4 applications to `dpo@mtp.com.br` because the
scraper stored the DPO's address as contact for 4 distinct MTP vacancies. The DPO
replied "privacy channel, not HR" — the applications were lost. The only uniqueness
is `(job_id, user_id)`, which stops two drafts for the same vacancy but not the same
recipient across distinct vacancies, the same vacancy from different sources, or
recreation after delete.

`contactEmail` lives on `Job`, not on `EmailDraft`. Enforcement therefore needs a
`recipientEmail` snapshot on the draft (value of `job.contactEmail` at generation /
send time) plus a partial unique index. This was a deliberate choice over
lookup-only guards, which cannot close the race between scheduler tick and manual
send.

---

## Expected behavior

### Scenario 1: generate skipped — same job + email already SENT
- **GIVEN** a `SENT` draft exists with `(jobId, recipientEmail)` and the job's current `contactEmail` equals that `recipientEmail` (non-null)
- **WHEN** `generate(userId, jobId)` is called (AI or template path)
- **THEN** no new `PENDING` is persisted
- **AND** the existing `SENT` draft is returned as-is, with a `DEBUG`-level log (`"Skipping generation, already sent to {} for job {}"`)

### Scenario 2: generate proceeds — contact changed or never sent
- **GIVEN** no `SENT` draft with `(jobId, currentContactEmail)`, or the job's `contactEmail` changed since the last send (`dpo@` → `rh@`), or `contactEmail` is `null`
- **WHEN** `generate(userId, jobId)` is called
- **THEN** generation proceeds normally (`PENDING`, or `REJECTED` per `email-no-apply-refusal.md`)
- **AND** the new draft snapshots `recipientEmail = job.contactEmail()` (possibly `null`)

### Scenario 3: send double-check — race blocked
- **GIVEN** a sendable draft (`PENDING`/`APPROVED`) for `(jobId, userId)` and the job's live `contactEmail` is non-null
- **WHEN** `send(userId, jobId)` runs and a *different* draft already `SENT` exists with `(jobId, liveContactEmail)`
- **THEN** throws `EmailAlreadySentException` (409, existing exception — idempotent signal, not a new error type)
- **AND** `EmailSenderPort.send` is never called, the current draft is untouched

### Scenario 4: send success stamps the snapshot
- **GIVEN** Scenario 3 finds no conflicting `SENT`
- **WHEN** `send(userId, jobId)` succeeds
- **THEN** the draft is persisted with `status = SENT`, `sentAt = now()`, `recipientEmail = liveContactEmail`

### Scenario 5: approve blocked on REJECTED (hardening, closes #28 hole)
- **GIVEN** a draft with `status == REJECTED`
- **WHEN** `approve(userId, jobId)` is called
- **THEN** throws `RefusedDraftException` (422, from #28) — `REJECTED` stays terminal unless regenerated via `generate()` (Scenario 5 of `email-no-apply-refusal.md`)

---

## Business rules

- Idempotency key is `(job_id, recipient_email)` across **all users** (global, as the issue proposes): two users sending to the same address for the same vacancy is treated as domain spam, not as two legitimate applications. `SENT` rows with `NULL` recipient never conflict (SQLite treats `NULL`s as distinct in unique indexes) — and such rows cannot exist via `send()` anyway (`MissingRecipientException` precedes persistence).
- Only `SENT` participates. `PENDING`/`APPROVED`/`REJECTED` duplicates are allowed and governed by their own lifecycle rules; regeneration `REJECTED → PENDING` keeps the same `id` but still passes Scenario 1 (a prior `SENT` for the pair wins — no resurrection of an already-sent pair).
- `recipientEmail` is a snapshot, never a live join: generation stamps the current `contactEmail` (enrichment may change `jobs.contact_email` later via `withContactEmail`); send re-resolves the live value for the double-check and overwrites the snapshot on success. If the contact changed, the key changed — resending is allowed by design.
- `DEBUG` log on every skip (both paths), following the `AutoSendEligibilityService` precedent. No warn/error noise: skips are expected control flow.
- Migration is `V4` (`V3__add_rejected_to_email_status.sql` already occupies V3; never edit V1–V3):
  ```sql
  ALTER TABLE email_drafts ADD COLUMN recipient_email VARCHAR(255);
  CREATE UNIQUE INDEX IF NOT EXISTS uq_email_drafts_sent_recipient
      ON email_drafts(job_id, recipient_email) WHERE status = 'SENT';
  ```
  SQLite ≥ 3.8 supports partial indexes; the `WHERE` literal must match the Java enum name exactly (`'SENT'`, case-sensitive). `ADD COLUMN` stays nullable (no backfill needed — old rows get `NULL`, which never conflicts).
- Port addition (application → infrastructure):
  ```java
  Optional<EmailDraft> findSentByJobIdAndRecipientEmail(Long jobId, String recipientEmail);
  ```
  JPA: derived query on `(jobId, recipientEmail, status='SENT')` via `findByJobIdAndRecipientEmailAndStatusIn`-style method with `List.of("SENT")`, following the existing `List<String> statuses` pattern — no `@Query` needed.
- Correction to the issue text: the repo has no `EmailDraftRepositoryImpl` / `EmailDraftJpaAdapter` — the classes are `EmailDraftPersistenceAdapter` (port impl) and `EmailDraftJpaRepository` (Spring Data). Tests are service-level with mocked `EmailDraftRepository` (per AGENTS.md test rules); no new persistence-test harness is introduced.

---

## Interface contract

```java
// Domain — one new nullable snapshot field (compact ctor unchanged: recipientEmail = null)
public record EmailDraft(
        Long id, Long jobId, Long userId,
        String subject, String body,
        EmailStatus status,
        LocalDateTime generatedAt, LocalDateTime sentAt,
        String recipientEmail
) {}
```

```java
// Output port — one new finder
public interface EmailDraftRepository {
    // ... existing ...
    Optional<EmailDraft> findSentByJobIdAndRecipientEmail(Long jobId, String recipientEmail);
}
```

No new exceptions. `EmailAlreadySentException` (409) covers the send double-check;
`RefusedDraftException` (422) covers the approve guard.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| `generate()` with pair already `SENT` | none — returns existing `SENT` | `DEBUG` log, no new row, no AI/template waste beyond the contact lookup |
| `send()` with pair already `SENT` by another draft | `EmailAlreadySentException` (existing, 409) | sender never called, current draft untouched |
| `approve()` on `REJECTED` | `RefusedDraftException` (existing, 422) | stays `REJECTED` |
| Partial-index violation on concurrent `save(SENT)` | `DataIntegrityViolationException` (Spring, infrastructure) | propagates as today (no new mapping); guards make it a last-resort backstop, not the primary path |
| `contactEmail == null` | unchanged (`MissingRecipientException` on send; snapshot `null` on generate) | idempotency checks skipped when either side is `null` |

---

## Out of scope

- Contact blocklist (`dpo@`, `privacy@`, `legal@`) and per-domain rate limiting — separate issues per #27.
- Fuzzy/keyword content validation — deferred in #28, stays deferred.
- `EmailStatus.valueOf()` fragility on garbage rows — pre-existing, unchanged.
- New persistence-test harness (H2/`@DataJpaTest`) — the repo has none; service-level mocked tests suffice for guards, and the partial index is plain Flyway SQL.

---

## Existing specs to update after GREEN

- `send-email.md`: "send() is not idempotent by design" is inverted — describe the double-check + 409.
- `generate-email.md`: check-on-create (Scenario 1/2) + `recipientEmail` in the `EmailDraft` contract.
- `email-no-apply-refusal.md`: remove the "idempotency (#27), implemented separately" out-of-scope line; note `REJECTED` interplay (Scenario 5 vs Scenario 1 here).
- `auto-send-scheduler.md`: silent skip + `DEBUG` (a tick that hits Scenario 3 ends quietly, draft untouched, retried never — pair is terminally `SENT`).
- `sqlite-local-persistence.md`: V4 (column + partial index), SQLite ≥ 3.8 requirement.

---

## Acceptance criteria (from #27, corrected to actual class names)

- [ ] `EmailGenerationServiceTest.generate_whenSameJobAndEmailAlreadySent_shouldReturnExistingSent` (no new row, sender/AI not re-invoked beyond lookup, `DEBUG` path)
- [ ] `EmailGenerationServiceTest.generate_whenDifferentEmailForSameJob_shouldGenerateNormally`
- [ ] `EmailSendingServiceTest.send_whenPairAlreadySent_shouldThrowAlreadySentAndNeverCallSender`
- [ ] `ApproveDraftServiceTest.approve_whenRejected_shouldThrowRefused`
- [ ] V4 migration (`recipient_email` + partial unique index `WHERE status = 'SENT'`)
- [ ] `DEBUG` log on both skips; contact change (`dpo@` → `rh@`) allows resend

---

# Spec extension: External application record (portal applies)

> **Layer:** `application` (new use case + service) + `web` (controller + DTO) + `infrastructure` (AppConfig wiring only — **zero migration**)
> **Issue:** the bot reports applies made directly on the portal (Gupy/InfoJobs) — no email involved — and the backend must keep the canonical applied record.
> **Corresponding tests:** `RecordExternalApplyServiceTest.java`, `JobControllerTest` (`recordExternalApply` group), `EmailGenerationServiceTest` (external-marker skip).

## Context

The Hermes bot also applies directly on portal sites (Gupy/InfoJobs) where the flow has no contact email. Those applications leave no trace in the backend. This adds `POST /api/jobs/{id}/applied` so the bot can record a portal apply as the canonical applied record — the same `SENT` signal the email flow produces.

## Decision: reuse SENT with a null-recipient marker, zero migration

Chosen over a new `EXTERNAL_APPLIED` status and over a new column:

- `draftStatus` already maps to "applied" (`excludeApplied`, job list filter) — a SENT marker is picked up with no filter change.
- `send()` and `approve()` already treat `SENT` as terminal; `generate()` gains a new guard (below), so a marker can never be re-sent, approved, or overwritten.
- The existing unique constraint `(job_id, user_id)` already guarantees one record per job per user; the partial index `(job_id, recipient_email) WHERE status = 'SENT'` is unaffected because `NULL` recipient values never conflict in SQLite unique indexes.
- A new enum value would be VARCHAR-only (no DDL) but would ripple through the AutoSend scheduler, eligibility/rate-limit counting, list filters, and every guard — blast radius with no behavioral return for a marker row.

Marker payload (stored `EmailDraft`):

- `status = SENT`, `recipientEmail = null`, `sentAt = now()`, `generatedAt = now()`
- `subject = "Subject: [Aplicação externa]"` (keeps the `Subject: ` prefix convention used by generated drafts)
- `body = "Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado."`

## Endpoint contract

`POST /api/jobs/{id}/applied` — no request body. User-scoped via `CurrentUserService`.

| Situation | Status | Response |
|---|---|---|
| Job does not exist | 404 | `JobNotFoundException` (existing mapping; no upsert of jobs) |
| First record for `(jobId, userId)` | 201 Created | `{ "jobId": id, "status": "SENT" }`; new marker row persisted |
| SENT marker already exists (replay) | 200 | `{ jobId, "status": "SENT" }`; existing row returned, nothing persisted |
| PENDING / APPROVED / REJECTED draft exists | 200 | same row id superseded **in place** to the SENT marker |
| Missing/invalid auth | 401 | `IllegalStateException` → 401 (existing mapping) |

## Business rules

- Idempotency is keyed on `(jobId, userId)`: a repeated POST is a replay, never a duplicate row — the lookup short-circuits before any save.
- Superseding keeps the existing draft `id` (update, not insert): exactly one row per `(job_id, user_id)` is preserved and the unique constraint is never violated.
- No AI call, no template, no email send: the service only depends on `JobRepository` and `EmailDraftRepository`.

## generate() guard (scope addition — required for the canonical record)

`EmailGenerationService.generate()` — a portal apply stores `recipientEmail = null`, so the `(jobId, recipientEmail)` pair check (email-idempotency Scenario 1 above) cannot see it. Without a new guard, `generate()` would resolve `existingId` via `findByJobIdAndUserId` and overwrite the SENT marker with a PENDING draft (resurrection ⇒ double application). The new guard runs before the pair check and covers both the AI and template paths:

```java
var existingSent = emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
        .filter(d -> d.status() == EmailStatus.SENT);
if (existingSent.isPresent()) {
    log.debug("Skipping generation, job {} already applied (SENT) for user {}", job.id(), userId);
    return existingSent.get();
}
```

## Interface contract

```java
// port/in — RecordExternalApplyUseCase
public interface RecordExternalApplyUseCase {
    RecordExternalApplyResult record(Long userId, Long jobId);
}

public record RecordExternalApplyResult(EmailDraft draft, boolean created) {}
```

DTO: `web/dto/ExternalApplyResponse(Long jobId, EmailStatus status)`.
Service: `application/service/RecordExternalApplyService(EmailDraftRepository, JobRepository)`, exposed as a `@Bean` in `AppConfig`.

## Acceptance criteria

- [ ] `RecordExternalApplyServiceTest.record_whenNoDraftExists_shouldCreateSentMarker` — created, SENT, null recipient, marker subject/body
- [ ] `RecordExternalApplyServiceTest.record_whenSentMarkerExists_shouldReturnExistingWithoutSaving`
- [ ] `RecordExternalApplyServiceTest.record_whenPendingDraftExists_shouldSupersedeInPlace` — same id, SENT, null recipient, no second row
- [ ] `RecordExternalApplyServiceTest.record_whenJobNotFound_shouldThrowJobNotFoundException`
- [ ] `JobControllerTest.recordExternalApply_whenCreated_shouldReturn201WithJobIdAndStatus`
- [ ] `JobControllerTest.recordExternalApply_whenReplay_shouldReturn200WithSameJobId`
- [ ] `JobControllerTest.recordExternalApply_whenJobMissing_shouldReturn404`
- [ ] `JobControllerTest.recordExternalApply_withoutAuthentication_shouldReturn401`
- [ ] `EmailGenerationServiceTest.generate_whenExternalApplyMarkerSent_shouldSkipRegeneration` — no AI, no save

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/email-idempotency.md
plus docs/specs/send-email.md, generate-email.md,
email-no-apply-refusal.md, and sqlite-local-persistence.md.

Step 1 — write the RED tests only (no production code):
- EmailGenerationServiceTest.generate_whenSameJobAndEmailAlreadySent_shouldReturnExistingSent
- EmailGenerationServiceTest.generate_whenDifferentEmailForSameJob_shouldGenerateNormally
- EmailSendingServiceTest.send_whenPairAlreadySent_shouldThrowAlreadySentAndNeverCallSender
- ApproveDraftServiceTest.approve_whenRejected_shouldThrowRefused
Mock EmailDraftRepository / JobRepository (Mockito, no Spring context).
Reference EmailDraft's new recipientEmail field and
EmailDraftRepository.findSentByJobIdAndRecipientEmail directly
(non-compiling references = valid RED for the new API).
Show the tests and ask "do you want to adjust before implementing?".

Step 2 — after confirmation, implement GREEN:
EmailDraft.recipientEmail + compact ctor default, port finder,
entity column + adapter mapping, JpaRepository finder,
generate() check-on-create, send() double-check + snapshot stamp,
approve() REJECTED guard, V4 migration (ADD COLUMN + partial index).
Keep collectUserBuckets() untouched.
Run mvn -Dtest='EmailGenerationServiceTest,EmailSendingServiceTest,ApproveDraftServiceTest' test.

Step 3 — after GREEN, update the five existing specs listed above.
Confirm which tests are GREEN.
```
