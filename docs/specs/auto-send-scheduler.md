# Spec: Auto-send scheduler

> **Layer:** `infrastructure` (scheduled task) + `application` (eligibility)
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.infrastructure.scheduler.AutoSendScheduler`
> - `com.juanperuzzo.job_hunter.application.service.AutoSendEligibilityService`
> **Corresponding tests:** `AutoSendSchedulerTest.java`, `AutoSendEligibilityServiceTest.java`
> **Depends on:** `send-email.md` (calls `SendEmailUseCase.send()` unchanged, doesn't reimplement it), `generate-email.md` (`EmailDraft`), `analyze-job.md` (`matchScore`)

---

## Expected behavior

### Scenario 1: full-auto mode, eligible draft exists
- **GIVEN** `auto-send.require-review = false` and `auto-send.enabled = true`
- **AND** a `PENDING` draft whose `Job.contactEmail` is non-null and whose `matchScore >= auto-send.min-match-score`
- **WHEN** a scheduler tick runs
- **THEN** `SendEmailUseCase.send(userId, jobId)` is called for exactly the oldest eligible draft
- **AND** nothing else happens this tick (one send per tick — see Business rules)

### Scenario 2: review-gate mode, draft not yet approved
- **GIVEN** `auto-send.require-review = true`
- **AND** a `PENDING` draft (not yet `APPROVED`)
- **WHEN** a tick runs
- **THEN** it is not selected — stays untouched until approved

### Scenario 3: review-gate mode, draft approved
- **GIVEN** `auto-send.require-review = true` and a draft with `status == APPROVED`
- **WHEN** a tick runs
- **THEN** sent, same as Scenario 1

### Scenario 4: no contact email
- **GIVEN** a draft whose `Job.contactEmail` is `null`
- **THEN** never selected by this scheduler, in either mode — permanently out of this channel, reserved for the future form-filling agent

### Scenario 5: below score threshold
- **GIVEN** a draft whose `Job.matchScore < auto-send.min-match-score`
- **THEN** not selected

### Scenario 6: nothing eligible
- **GIVEN** no draft matches the criteria
- **WHEN** a tick runs
- **THEN** does nothing, no error, no log noise beyond debug level

### Scenario 7: approve a draft
- **GIVEN** a `PENDING` draft
- **WHEN** `POST /api/jobs/{id}/email/approve` (or `jh-cli email approve <job-id>`) is called
- **THEN** status becomes `APPROVED`
- **AND** throws if the draft is already `SENT` or already `APPROVED` (not idempotent, same philosophy as `send()`)

### Scenario 8: send fails mid-tick
- **GIVEN** `SendEmailUseCase.send()` throws (e.g. delivery failure)
- **WHEN** a tick runs
- **THEN** the exception is caught and logged, the tick ends quietly
- **AND** the draft is untouched (stays `PENDING`/`APPROVED`, since `send()` itself already guarantees no status change on failure) — picked up again on the next tick, no crash loop

---

## Business rules

- **One send per tick, by design.** `@Scheduled(fixedDelayString = "${auto-send.interval-seconds:120}000")` — the scheduler's own interval is the spacing between sends. No in-method loop with `Thread.sleep()`: that would block a thread for the whole backlog and lose progress cleanly on restart. Add a small random jitter on top of the configured delay (up to +30s) so it isn't perfectly metronomic.
- **`EmailStatus` gains a third value:** `APPROVED`, sitting between `PENDING` and `SENT`. Only meaningful when `require-review = true`; ignored when `false` (eligibility reads `PENDING` directly in that mode).
- **Selection order:** oldest eligible draft first (by `generatedAt`), so nothing sits forever if a backlog builds up.

---

## Interface contract

```java
public interface AutoSendEligibilityPort {
    // Oldest eligible draft: contactEmail present, score above threshold,
    // and (PENDING if require-review=false) or (APPROVED if true).
    Optional<EmailDraft> nextEligibleDraft();
}
```

```java
@Scheduled(fixedDelayString = "${auto-send.interval-seconds:120}000")
void tick() {
    if (!autoSendEnabled) return;
    eligibilityPort.nextEligibleDraft().ifPresent(draft -> {
        try {
            sendEmailUseCase.send(draft.userId(), draft.jobId());
        } catch (Exception e) {
            log.warn("Auto-send failed for job {}: {}", draft.jobId(), e.getMessage());
        }
    });
}
```

Requires `@EnableScheduling` somewhere in `AppConfig` — nothing in the codebase uses `@Scheduled` yet, so this is a new capability, not just a new bean.

---

## Error cases

| Situation | Behavior |
|---|---|
| `send()` throws mid-tick | logged at `WARN`, tick ends, draft unchanged, retried next tick |
| Approve called on `SENT` or already-`APPROVED` draft | throws (reuse the `EmailAlreadySentException` pattern, or a sibling exception) |
| `auto-send.enabled = false` | scheduler method still fires on schedule but returns immediately — cheap no-op, not worth skipping registration over |

---

## Out of scope

- The form-filling agent (Hermes/Cua) for Gupy/LinkedIn — separate spec, different risk profile (ToS/account-ban), not triggered by this scheduler
- Automatic retry/backoff for failed sends — a failed send just re-enters the normal selection pool next tick, no special-cased retry logic for V1
- Per-company rules ("always auto-send to companies I've marked as trusted") — later idea, not now

---

## Configuration

```yaml
auto-send:
  enabled: false            # master switch
  require-review: true      # the "no máximo uma revisão manual" ceiling — flip to false once trusted
  interval-seconds: 120
  jitter-seconds: 30
  min-match-score: 70       # only auto-send jobs scoring at least this
  daily-cap: 10             # max auto-sends per day across all drafts
```

---

## Open questions (answered)

### Q1 — Score distribution and min-match-score

`analyze-job.md` defines `matchScore` as 0–100, clamped. Without real production data on distribution, **70** is a reasonable starting threshold — confident enough that the match is serious, but not so high that it never fires. Configurable anyway.

### Q2 — Daily cap

Yes, worth having even with `require-review: true` as default. **10/day** is a safe starting ceiling — prevents accidental blast even if you flip to full-auto. Configurable.
