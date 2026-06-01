# Spec: Generate application email

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.EmailGenerationService`
> **Corresponding test:** `EmailGenerationServiceTest.java`
> **HTTP & multi-user behavior:** see `user-scoped-analysis.md` (`JobController` loads analysis before calling this service)

---

## Expected behavior

### Scenario 1: successful generation
- **GIVEN** a valid `Job`, `JobAnalysis`, and saved user profile
- **WHEN** `generate(userId, job, analysis)` is called
- **THEN** returns an `EmailDraft` with `subject` and `body` populated
- **AND** `subject` starts with "Subject: " (standard prefix)
- **AND** `body` has at most 3 paragraphs

### Scenario 2: analysis with low matchScore
- **GIVEN** a `JobAnalysis` with `matchScore < 30`
- **WHEN** `generate(userId, job, analysis)` is called
- **THEN** generation proceeds normally — not blocked by low score
- **AND** the email mentions willingness to learn the missing skills

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
- **WHEN** `generate(userId, job, analysis)` is called
- **THEN** throws `AiException`

---

## Business rules

- The prompt includes the user's resume and skills from `user_profiles` (not the user's display name)
- The email must mention at least 1 candidate project (listed in the prompt template)
- Maximum 3 paragraphs in the `body`
- The `subject` is extracted from the first line of the AI response (prefix `"Subject: "`)
- The `body` is the remainder of the response after removing the subject line
- `EmailDraft` is saved with `userId`, `jobId`, and `status = PENDING`
- Per-user uniqueness: one draft per `(job_id, user_id)` enforced at database level (V4 migration)

---

## Interface contract (port)

```java
// Input port
public interface GenerateEmailUseCase {
    EmailDraft generate(Long userId, Job job, JobAnalysis analysis);
}

// Result (persisted)
public record EmailDraft(
    Long id,
    Long jobId,
    Long userId,
    String subject,
    String body,
    EmailStatus status,
    LocalDateTime generatedAt
) {}

public enum EmailStatus { PENDING, SENT }
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Null `userId`, `job`, or `analysis` | `NullPointerException` | Fails immediately |
| Profile missing in DB | `AiException` | `"User profile not found for userId: ..."` |
| AI unavailable | `AiException` | Propagates without saving draft |

**Note:** `AnalysisNotFoundException` is thrown by `JobController` when `POST /api/jobs/{id}/email` is called without a prior analysis, not by this service.

---

## Out of scope

- Sending the email (only generates and persists the draft)
- Loading analysis from the database (`JobController` responsibility)
- Validating AI output against business rules (trusts prompt + model)

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
