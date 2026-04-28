# Spec: Analyze job with AI

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.AiAnalysisService`
> **Corresponding test:** `AiAnalysisServiceTest.java`

---

## Expected behavior

### Scenario 1: successful analysis
- **GIVEN** a `Job` with a populated description
- **WHEN** `analyze(job)` is called
- **THEN** returns a `JobAnalysis` with `matchScore` between 0 and 100
- **AND** `matchedSkills` is a non-null list (may be empty)
- **AND** `missingSkills` is a non-null list (may be empty)
- **AND** `companyTone` is one of: `FORMAL`, `CASUAL`, `STARTUP`

### Scenario 2: job with empty description
- **GIVEN** a `Job` with an empty or blank `description`
- **WHEN** `analyze(job)` is called
- **THEN** throws `IllegalArgumentException`
- **AND** the AI is not called

### Scenario 3: AI returns invalid JSON
- **GIVEN** the AI client returns an unparseable response
- **WHEN** `analyze(job)` is called
- **THEN** throws `AiException` with a descriptive message

### Scenario 4: AI unavailable
- **GIVEN** the AI client throws a network exception
- **WHEN** `analyze(job)` is called
- **THEN** propagates `AiException`

### Scenario 5: matchScore out of range
- **GIVEN** the AI returns `matchScore: 150` (invalid)
- **WHEN** the result is processed
- **THEN** the score is clamped to 100 (maximum)

---

## Business rules

- `matchScore` must be between 0 and 100 — out-of-range values are clamped
- The analysis is not persisted automatically — the caller decides whether to save it
- The prompt sent to the AI includes the candidate's fixed profile (defined in `docs/prompts.md`)
- The result is always parsed from JSON — the AI is instructed not to include markdown

---

## Interface contract (port)

```java
// Input port
public interface AnalyzeJobUseCase {
    JobAnalysis analyze(Job job);
}

// Analysis result
public record JobAnalysis(
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
| Empty description | `IllegalArgumentException` | Fails before calling AI |
| Invalid JSON from AI | `AiException` | Message includes received body |
| Timeout / network | `AiException` | Wraps the original cause |

---

## Out of scope

- Does not persist the result
- Does not generate the email (that is `generate-email.md`)
- Does not cache the analysis

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/analyze-job.md
and the AI prompts at docs/prompts.md.

Step 1 — write AiAnalysisServiceTest at
src/test/java/.../application/AiAnalysisServiceTest.java
Mock AiPort with Mockito. Cover all scenarios. RED.

Step 2 — after my confirmation, implement AiAnalysisService.
The analyze() method must build the prompt, call AiPort,
parse the JSON, and return JobAnalysis.

Step 3 — after my confirmation, refactor the parsing
into a private method parseAnalysis(String json).
```
