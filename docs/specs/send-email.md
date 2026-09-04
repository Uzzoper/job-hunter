# Spec: Send application email

> **Layer:** `application` (use case) + `infrastructure` (SMTP adapter)
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.EmailSendingService`
> **Corresponding test:** `EmailSendingServiceTest.java`
> **Depends on:** `generate-email.md` (an `EmailDraft` with status `PENDING` must already exist)

---

## Expected behavior

### Scenario 1: successful send
- **GIVEN** an `EmailDraft` with `status == PENDING` and a resolvable recipient email
- **WHEN** `send(userId, jobId)` is called
- **THEN** `EmailSenderPort.send(from, to, subject, body)` is called once
- **AND** the draft is persisted with `status = SENT` and `sentAt = now()`
- **AND** the returned `EmailDraft` reflects the updated status

### Scenario 2: draft already sent
- **GIVEN** an `EmailDraft` with `status == SENT`
- **WHEN** `send(userId, jobId)` is called
- **THEN** throws `EmailAlreadySentException`
- **AND** `EmailSenderPort.send` is never called (no duplicate delivery)

### Scenario 3: no recipient email resolvable
- **GIVEN** a `Job` with no `contactEmail` (e.g. a Gupy or LinkedIn listing — application happens through the platform's own form, not email)
- **WHEN** `send(userId, jobId)` is called
- **THEN** throws `MissingRecipientException` with message like `"Job {id} ({url}) has no contact email"`
- **AND** the exception message includes the job's `url` for manual/form-based application

### Scenario 4: delivery failure
- **GIVEN** `EmailSenderPort.send` throws
- **WHEN** `send(userId, jobId)` is called
- **THEN** throws `EmailDeliveryException`
- **AND** the draft is left as `PENDING` (safe to retry — never marked `SENT` on failure)

---

## Business rules

- Only a draft with `status == PENDING` (full-auto) or `APPROVED` (review-gate) may be sent; `REJECTED` drafts (see `email-no-apply-refusal.md`) throw `RefusedDraftException` (422) before any send attempt; `send()` is not idempotent by design (see Scenario 2)
- On success, `status` and `sentAt` are updated in the same persistence call (no partial state)
- No automatic/scheduled triggering here — this spec only covers an explicit, user- or scheduler-initiated single send (e.g. `POST /api/jobs/{id}/email/send`, `jh-cli email send <job-id>`, or one call from `auto-send-scheduler.md`)

### Recipient resolution

`Job` gains a nullable `contactEmail` field, populated in `JobNormalizer` via a best-effort regex scan of the listing description for an email address. No AI involved — a regex is reliable enough for this and cheaper. Gupy and LinkedIn listings are almost always form-based and will stay `null`, which is expected, not an error: `send()` still throws `MissingRecipientException` for those (Scenario 3), which is exactly the signal the future form-filling agent is meant to pick up instead. This service only reads `Job.contactEmail`; it does not derive it.

---

## Interface contract

### Input port (use case)

```java
public interface SendEmailUseCase {
    EmailDraft send(Long userId, Long jobId);
}
```

### Output port (infrastructure detail — SMTP, or any transactional-email provider)

```java
public interface EmailSenderPort {
    void send(String from, String to, String subject, String body);
}
```

### Extended EmailDraft record

`EmailDraft` gains two fields:

```java
public record EmailDraft(
        Long id,
        Long jobId,
        Long userId,
        String subject,
        String body,
        EmailStatus status,
        LocalDateTime generatedAt,
        LocalDateTime sentAt   // null until SENT
) {}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| No draft exists for `(jobId, userId)` | `JobNotFoundException` | fails immediately (reuses existing exception, consistent with `GetEmailDraftUseCase`) |
| Draft status == `SENT` | `EmailAlreadySentException` (new) | fails, no send attempted |
| `Job.contactEmail` is null | `MissingRecipientException` (new) | fails, message points to `Job.url` |
| `EmailSenderPort.send` throws | `EmailDeliveryException` (new) | propagates, draft stays `PENDING` |

---

## Out of scope

- Automatic or scheduled sending, review-gating, and everything match-score/threshold related — separate spec (`auto-send-scheduler.md`), which calls this use case, it doesn't change it
- Submitting applications through a platform's own form (Gupy, LinkedIn Easy Apply, InfoJobs) — that's browser/agent automation, not email, and belongs in its own spec (`form-application-agent.md`) given it carries real ToS and account-ban risk that plain email sending doesn't

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/send-email.md.

Step 1 — write EmailSendingServiceTest at
src/test/java/.../application/EmailSendingServiceTest.java
Mock EmailSenderPort and EmailDraftRepository. Cover all 4 scenarios. RED.
Do not write the implementation yet.

Step 2 — wait for confirmation before implementing.

Step 3 — after confirmation, implement EmailSendingService
and a Resend-backed EmailSenderPort adapter (RestClient,
same pattern as OpenRouterClient) under infrastructure/email/.
No new Spring Boot starter needed — it's a plain HTTP call.
```

> **Update (2026-08):** the Resend-backed adapter was replaced by
> `HermesBotEmailSender` (Hermes Agent bot delegation) — see
> `hermes-agent-integration.md`. The `EmailSendingService` scenarios above are unchanged.
