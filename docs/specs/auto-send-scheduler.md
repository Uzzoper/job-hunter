# Spec: Auto-send scheduler

> **Layer:** `infrastructure` (scheduled task) + `application` (eligibility)
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.infrastructure.scheduler.AutoSendScheduler`
> - `com.juanperuzzo.job_hunter.application.service.AutoSendEligibilityService`
> **Corresponding tests:** `AutoSendSchedulerTest.java`, `AutoSendEligibilityServiceTest.java`
> **Depends on:** `send-email.md` (calls `SendEmailUseCase.send()`), `generate-email.md` (`EmailDraft`), `analyze-job.md` (`matchScore`)

---

## Expected behavior

### Scenario 1: high-score eligible, full-auto mode
- **GIVEN** `auto-send.require-review = false` and `auto-send.enabled = true`
- **AND** a `PENDING` draft whose `Job.contactEmail` is non-null and `matchScore >= 60`
- **WHEN** a scheduler tick runs
- **THEN** `EligibilityService` selects the highest-scoring eligible draft
- **AND** `SendEmailUseCase.send(userId, jobId)` is called
- **AND** the draft is marked `SENT`

### Scenario 2: high-score eligible, review-gate mode
- **GIVEN** `auto-send.require-review = true` and a draft with `status == APPROVED` and `matchScore >= 60`
- **WHEN** a tick runs
- **THEN** same as Scenario 1 (selects highest-scoring eligible draft, sends it)

### Scenario 3: low-score eligible
- **GIVEN** a draft with `matchScore < 60` (but >= floor) and `contactEmail` non-null
- **WHEN** a tick runs and no higher-score draft is waiting
- **THEN** `EligibilityService` selects it by score priority
- **AND** `SendEmailUseCase.send(userId, jobId)` is called (email was already generated earlier by `EmailGenerationService`)

### Scenario 4: review-gate mode, draft not yet approved
- **GIVEN** `auto-send.require-review = true`
- **AND** a draft with `status == PENDING` (not yet `APPROVED`)
- **WHEN** a tick runs
- **THEN** it is not selected — stays untouched until approved

### Scenario 5: no contact email
- **GIVEN** a draft whose `Job.contactEmail` is `null`
- **THEN** never selected by this scheduler, in either mode — reserved for the future form-filling agent

### Scenario 6: nothing eligible
- **GIVEN** no draft matches the criteria (including the case where only `REJECTED` drafts exist — see `email-no-apply-refusal.md`)
- **WHEN** a tick runs
- **THEN** does nothing, no error, no log noise beyond debug level

### Scenario 7: approve a draft
- **GIVEN** a `PENDING` draft
- **WHEN** `POST /api/jobs/{id}/email/approve` (or `jh-cli email approve <job-id>`) is called
- **THEN** status becomes `APPROVED`
- **AND** throws if the draft is already `SENT` or already `APPROVED`

### Scenario 8: send fails mid-tick
- **GIVEN** `SendEmailUseCase.send()` throws (delivery failure, or the idempotency double-check `EmailAlreadySentException` — see `email-idempotency.md`)
- **WHEN** a tick runs
- **THEN** the exception is caught and logged, the tick ends quietly
- **AND** the draft is untouched (stays `PENDING`/`APPROVED`) — retried next tick (except an idempotent pair, which is terminally `SENT` elsewhere and never becomes sendable)

### Scenario 9: daily cap reached
- **GIVEN** `DailyCap` (`auto-send.daily-cap: 50`) has been reached for the current user
- **WHEN** a tick runs
- **THEN** nothing is sent, regardless of eligibility
- **AND** resets at midnight UTC

---

## Business rules

- **One send per tick, by design.** `@Scheduled(fixedDelayString = "${auto-send.interval-seconds:120}000")` — the scheduler's own interval is the spacing between sends. No in-method loop with `Thread.sleep()`. Add a small random jitter (up to +30s).
- **Priority order: highest matchScore first.** Within the same score tier, oldest first.
- **Scheduler does not generate emails.** Email generation is handled separately by `EmailGenerationService` (high-score → template, low-score → AI). The scheduler only calls `send()`.
- **`EmailStatus` gains `APPROVED`:** sits between `PENDING` and `SENT`. Only meaningful when `require-review = true`; full-auto mode reads `PENDING` directly.
- **Daily cap:** 50 per user per calendar day (UTC). Resets at midnight.
- **Feasibility with local AI (qwen2.5:3b):** Yes. The tick interval and 50/day cap keep the scheduler comfortable regardless of email type.

---

## Interface contract

```java
public interface AutoSendEligibilityUseCase {
    // Highest-scoring eligible draft across all users.
    // Filters: contactEmail non-null, daily cap not hit, correct status
    // (PENDING in full-auto, APPROVED in review-gate).
    // Returns EligibleDraft (draft, matchScore, company, contactEmail, jobTitle)
    // or empty if none eligible.
    Optional<EligibleDraft> nextEligibleDraft();
}
```

```java
@Scheduled(fixedDelayString = "${auto-send.interval-seconds:120}000")
void tick() {
    if (!enabled) return;
    // optional jitter before selection
    eligibilityPort.nextEligibleDraft().ifPresent(eligible -> {
        try {
            sendEmailUseCase.send(eligible.draft().userId(), eligible.draft().jobId());
        } catch (Exception e) {
            log.warn("Auto-send failed for job {}: {}", eligible.draft().jobId(), e.getMessage());
        }
    });
}
```

Requires `@EnableScheduling` in `AppConfig`.

---

## Error cases

| Situation | Behavior |
|---|---|
| `send()` throws mid-tick | logged at `WARN`, tick ends, draft unchanged, retried next tick |
| Approve called on `SENT` or already-`APPROVED` draft | throws |
| `auto-send.enabled = false` | scheduler fires but returns immediately — no-op |

---

## Out of scope

- The form-filling agent for Gupy/LinkedIn — separate spec, different risk profile
- Automatic retry/backoff — failed send re-enters selection pool next tick
- Per-company rules — later

---

## Configuration

```yaml
auto-send:
  enabled: false            # master switch
  require-review: true      # require manual approval before auto-send
  interval-seconds: 120
  jitter-seconds: 30
  daily-cap: 50             # max auto-sends per user per calendar day
```

No `min-match-score` — score determines email type (>= 60 template, < 60 personalized), not whether to send. Every eligible draft is auto-sent in priority order.
