# Spec: Generate application email

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.EmailGenerationService`
> **Corresponding test:** `EmailGenerationServiceTest.java`

---

## Expected behavior

### Scenario 1: successful generation
- **GIVEN** a valid `Job` and `JobAnalysis`
- **WHEN** `generate(job, analysis)` is called
- **THEN** returns an `EmailDraft` with `subject` and `body` populated
- **AND** `subject` starts with "Subject: " (standard prefix)
- **AND** `body` has at most 3 paragraphs

### Scenario 2: analysis with low matchScore
- **GIVEN** a `JobAnalysis` with `matchScore < 30`
- **WHEN** `generate(job, analysis)` is called
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
- **WHEN** `generate(job, analysis)` is called
- **THEN** throws `AiException`

---

## Business rules

- The email must mention at least 1 candidate project
- The mentioned project should be relevant to the job — chosen based on `matchedSkills`
- Maximum 3 paragraphs in the `body`
- The `subject` is extracted from the first line of the AI response (prefix `"Subject: "`)
- The `body` is the remainder of the response after removing the subject line
- `EmailDraft` starts with `status = PENDING` — never `SENT`

---

## Interface contract (port)

```java
// Input port
public interface GenerateEmailUseCase {
    EmailDraft generate(Job job, JobAnalysis analysis);
}

// Result
public record EmailDraft(
    Long id,
    Long jobId,
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
| Null `job` | `NullPointerException` | Fails immediately |
| Null `analysis` | `NullPointerException` | Fails immediately |
| AI unavailable | `AiException` | Propagates without saving draft |

---

## Out of scope

- Does not send the email (only generates the draft)
- Does not persist automatically
- Does not validate whether the generated email follows business rules (trusts AI + prompt)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/generate-email.md
and the generation prompt at docs/prompts.md.

Step 1 — write EmailGenerationServiceTest at
src/test/java/.../application/EmailGenerationServiceTest.java
Mock AiPort. Cover all scenarios. RED.

Step 2 — after my confirmation, implement EmailGenerationService.
It must build the prompt with tone and skills, call AiPort,
parse subject and body, and return EmailDraft with status PENDING.

Step 3 — after my confirmation, refactor by extracting
buildPrompt(Job, JobAnalysis) and parseEmailDraft(String).
```
