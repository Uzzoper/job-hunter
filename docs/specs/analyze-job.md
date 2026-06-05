# Spec: Analyze job with AI

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.AiAnalysisService`
> **Corresponding test:** `AiAnalysisServiceTest.java`
> **HTTP & multi-user behavior:** see `user-scoped-analysis.md`

---

## Expected behavior

### Scenario 0: profile required (multi-user)
- **GIVEN** no row in `user_profiles` for the given `userId`
- **WHEN** `analyze(userId, job)` is called
- **THEN** throws `ProfileNotConfiguredException` with message `"Please configure your resume and skills profile first"`
- **AND** the AI is not called

### Scenario 1: successful analysis
- **GIVEN** a `Job` with a populated description and a saved user profile
- **WHEN** `analyze(userId, job)` is called
- **THEN** returns a `JobAnalysis` with `matchScore` between 0 and 100
- **AND** `matchedSkills` is a non-null list (may be empty)
- **AND** `missingSkills` is a non-null list (may be empty)
- **AND** `companyTone` is one of: `FORMAL`, `CASUAL`, `STARTUP`

### Scenario 2: job with empty description
- **GIVEN** a `Job` with an empty or blank `description`
- **WHEN** `analyze(userId, job)` is called
- **THEN** throws `IllegalArgumentException`
- **AND** the AI is not called

### Scenario 3: AI returns invalid JSON
- **GIVEN** the AI client returns an unparseable response
- **WHEN** `analyze(userId, job)` is called
- **THEN** throws `AiException` with a descriptive message

### Scenario 4: AI unavailable
- **GIVEN** the AI client throws a network exception
- **WHEN** `analyze(userId, job)` is called
- **THEN** propagates `AiException`

### Scenario 5: matchScore out of range
- **GIVEN** the AI returns `matchScore: 150` (invalid)
- **WHEN** the result is processed
- **THEN** the score is clamped to 100 (maximum)

---

## Business rules

- `matchScore` must be between 0 and 100 — out-of-range values are clamped
- The service persists each analysis to `job_analyses` with `job_id` and `user_id`
- The prompt includes the user's saved profile (resume, skills, tone) from `user_profiles`
- Prompt structure follows `docs/specs/prompts.md` (implementation may inline equivalent instructions)
- The result is always parsed from JSON — the AI is instructed not to include markdown

---

## Interface contract (port)

```java
// Input port
public interface AnalyzeJobUseCase {
    JobAnalysis analyze(Long userId, Job job);
}

// Analysis result (persisted)
public record JobAnalysis(
    Long id,
    Long jobId,
    Long userId,
    int matchScore,
    List<String> matchedSkills,
    List<String> missingSkills,
    CompanyTone companyTone,
    String summary
) {}

public enum CompanyTone { FORMAL, CASUAL, STARTUP }

// Output port — AI client
public interface AiPort {
    String complete(String prompt);
}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| No saved user profile | `ProfileNotConfiguredException` | Fails before calling AI |
| Empty description | `IllegalArgumentException` | Fails before calling AI |
| Invalid JSON from AI | `AiException` | Message includes received body |
| Timeout / network | `AiException` | Wraps the original cause |

---

## Out of scope

- HTTP security and `JobController` orchestration (`user-scoped-analysis.md`)
- Email generation (`generate-email.md`)
- Caching or upsert on duplicate analyze for same user+job

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/analyze-job.md
and the AI prompts at docs/specs/prompts.md.

Step 1 — write AiAnalysisServiceTest at
src/test/java/.../application/AiAnalysisServiceTest.java
Mock AiPort with Mockito. Cover all scenarios. RED.

Step 2 — after my confirmation, implement AiAnalysisService.
The analyze() method must build the prompt, call AiPort,
parse the JSON, and return JobAnalysis.

Step 3 — after my confirmation, refactor the parsing
into a private method parseAnalysis(String json).
```
