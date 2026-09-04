# Spec: Generate application email

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.EmailGenerationService`
> **Corresponding test:** `EmailGenerationServiceTest.java`
> **HTTP & multi-user behavior:** see `user-scoped-analysis.md` (`JobController` loads analysis before calling this service)

---

## Expected behavior

### Scenario 1: successful generation (AI path, matchScore < threshold)
- **GIVEN** a valid `Job`, `JobAnalysis` with `matchScore < threshold`, and saved user profile
- **WHEN** `generate(userId, jobId)` is called
- **THEN** builds prompt from job/analysis/profile, calls `AiPort.complete()`
- **AND** returns an `EmailDraft` with `subject` and `body` populated from the AI response
- **AND** `subject` starts with "Subject: " (standard prefix)
- **AND** `body` has 3-5 paragraphs

### Scenario 2: analysis with low matchScore
- **GIVEN** a `JobAnalysis` with `matchScore < 30`
- **WHEN** `generate(userId, jobId)` is called
- **THEN** generation proceeds normally — not blocked by low score
- **AND** the email addresses missing skills matter-of-factly — professional positioning, no trainee phrasing

### Scenario 3: formal tone
- **GIVEN** `companyTone == FORMAL`
- **WHEN** the prompt is built
- **THEN** the prompt instructs the AI to use formal language

### Scenario 4: startup tone
- **GIVEN** `companyTone == STARTUP`
- **WHEN** the prompt is built
- **THEN** the prompt instructs the AI to use casual, energetic language

### Scenario 5: AI unavailable
- **GIVEN** the AI client throws an exception
- **WHEN** `generate(userId, jobId)` is called
- **THEN** throws `AiException`

### Scenario 6: template branch for high matchScore — NEW
- **GIVEN** `analysis.matchScore() >= threshold` (default: 60, configurable via `email.standard-template.min-match-score`)
- **WHEN** `generate(userId, jobId)` is called
- **THEN** `AiPort.complete()` is **never** called
- **AND** the returned `EmailDraft` has `subject` and `body` built from `TemplateEmailService` with both `job.title()` and `job.company()` substituted
- **AND** the draft is persisted with `status = PENDING` through the same upsert path

---

## Business rules

- The prompt includes the user's resume and skills from `user_profiles` (not the user's display name)
- The email must mention at least 1 candidate project (listed in the prompt template)
- Maximum 3-5 paragraphs in the `body`
- The `subject` is extracted from the first line of the AI response (prefix `"Subject: "`)
- The `body` is the remainder of the response after removing the subject line
- `EmailDraft` is saved with `userId`, `jobId`, and `status = PENDING` — or `REJECTED` when the AI returns the `NO_APPLY:` refusal (see `email-no-apply-refusal.md`)
- Per-user uniqueness: one draft per `(job_id, user_id)` enforced at database level (see `email-no-apply-refusal.md` for the reconciled migration numbering — the earlier `V3` mention here predates the actual `V3__add_rejected_to_email_status.sql`)
- When `matchScore >= minMatchScore`, a fixed template replaces the AI call entirely (saves AI credits, deterministic output)
- The threshold is configured via `email.standard-template.min-match-score` (default: 60)

---

## Interface contract (ports)

```java
// Input port
public interface GenerateEmailUseCase {
    EmailDraft generate(Long userId, Long jobId);
}

// Result (persisted)
public record EmailDraft(
    Long id,
    Long jobId,
    Long userId,
    String subject,
    String body,
    EmailStatus status,
    LocalDateTime generatedAt,
    LocalDateTime sentAt
) {}

public enum EmailStatus { PENDING, APPROVED, SENT, REJECTED }
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Null `userId` or `jobId` | `NullPointerException` | Fails immediately |
| Job not found in DB | `JobNotFoundException` | Returns 404 |
| Analysis not found | `AnalysisNotFoundException` | Returns 400 |
| Profile missing in DB | `AiException` | `"User profile not found for userId: ..."` |
| AI unavailable (template branch) | never thrown — template branch doesn't call AI | Returns template draft |
| AI unavailable (AI branch) | `AiException` | Propagates without saving draft |

---

## Out of scope

- Sending the email (only generates and persists the draft)
- Loading analysis from the database (`JobController` responsibility)
- Validating AI output against business rules is covered by `email-no-apply-refusal.md` (strict `NO_APPLY:` prefix → `REJECTED`); no fuzzy validation

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/generate-email.md
and the generation prompt at docs/specs/prompts.md.

Step 1 — write EmailGenerationServiceTest at
src/test/java/.../application/EmailGenerationServiceTest.java
Mock AiPort. Cover all scenarios. RED.

Step 2 — after my confirmation, implement EmailGenerationService.
It must build the prompt with tone and skills, call AiPort,
parse subject and body, and return EmailDraft with status PENDING.

Step 3 — after my confirmation, refactor by extracting
buildPrompt(Job, JobAnalysis) and parseEmailDraft(String).
```
