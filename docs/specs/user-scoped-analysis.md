# Spec: User-Scoped Job Analysis & Email Generation

> **Layer:** `application` | `domain` | `web` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.application.service.AiAnalysisService`
> - `com.juanperuzzo.job_hunter.application.service.EmailGenerationService`
> - `com.juanperuzzo.job_hunter.web.controller.JobController`
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.JobAnalysisPersistenceAdapter`
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.EmailDraftPersistenceAdapter`
> **Corresponding tests:** `AiAnalysisServiceTest.java`, `EmailGenerationServiceTest.java`, `JobControllerTest.java`, `AuthIntegrationTest.java`
> **Related specs:** `analyze-job.md` (service-level analysis rules), `generate-email.md` (service-level email rules), `prompts.md` (prompt reference)

---

## Expected behavior

Job analysis and email drafts are scoped per authenticated user. Analysis and generation use the user's saved profile (resume + skills), not a hardcoded candidate profile.

### Scenario 1: Run User-Scoped AI Analysis
- **GIVEN** an authenticated user with a saved profile (`PUT /api/profile` completed)
- **WHEN** they call `POST /api/jobs/{id}/analyze`
- **THEN** the system loads the job and the user's profile from the database
- **AND** sends both to OpenRouter via `AiPort` (prompt built in `AiAnalysisService`, aligned with `docs/specs/prompts.md`)
- **AND** persists the result in `job_analyses` with `job_id` and `user_id` (`UNIQUE(job_id, user_id)`)
- **AND** returns HTTP 200 OK with the `JobAnalysis` JSON.

### Scenario 2: Generate Email Draft for User
- **GIVEN** a job already analyzed for the active user (`job_analyses` row exists)
- **WHEN** they call `POST /api/jobs/{id}/email`
- **THEN** the system loads the existing analysis (does **not** re-run analysis)
- **AND** uses profile CV, skills, and analysis results to generate the email via AI
- **AND** saves the draft in `email_drafts` with `job_id` and `user_id`
- **AND** returns HTTP 200 OK with `EmailDraftResponse`.

### Scenario 3: Read Email Draft
- **GIVEN** an authenticated user who generated a draft for a job
- **WHEN** they call `GET /api/jobs/{id}/email`
- **THEN** returns HTTP 200 with their draft only (`findByJobIdAndUserId`).
- **AND** another user requesting the same `job_id` receives HTTP 404 (no cross-user leakage).

---

## Recommended API flow

```
POST /api/auth/register|login  →  obtain token
PUT  /api/profile              →  save resume + skills (required before analyze)
POST /api/jobs/fetch           →  optional: scrape new jobs
GET  /api/jobs                 →  pick a job id
POST /api/jobs/{id}/analyze    →  create analysis
POST /api/jobs/{id}/email      →  generate draft (requires prior analyze)
GET  /api/jobs/{id}/email      →  read draft
```

---

## Business rules

- **Score & skills:** `matchScore`, `matchedSkills`, and `missingSkills` are produced by the AI comparing the job description to the user's profile (skills listed in the prompt); there is no separate rule engine in code.
- **Data isolation:** All analysis and email queries filter by the logged-in user's id. Migration `V4__enforce_email_drafts_user_scope.sql` enforces `email_drafts.user_id NOT NULL` and `UNIQUE(job_id, user_id)`.
- **Jobs listing:** `GET /api/jobs` returns shared job postings; only analyses and drafts are per-user.
- **Breaking change:** `match_score` was removed from the `jobs` table (V3); scores live in `job_analyses` per user.

---

## Interface contract

### HTTP — `JobController`

| Method | Path | Auth | Response |
|--------|------|------|----------|
| GET | `/api/jobs` | Bearer | `200` list of `JobResponse` |
| GET | `/api/jobs/{id}` | Bearer | `200` `JobResponse` |
| POST | `/api/jobs/fetch` | Bearer | `200` `{ "message": "..." }` |
| POST | `/api/jobs/{id}/analyze` | Bearer | `200` `JobAnalysis` |
| POST | `/api/jobs/{id}/email` | Bearer | `200` `EmailDraftResponse` |
| GET | `/api/jobs/{id}/email` | Bearer | `200` `EmailDraftResponse` |

`JobResponse` fields: `id`, `title`, `company`, `url`, `description`, `postedAt` (no `matchScore`).

### Application ports

```java
public interface AnalyzeJobUseCase {
    JobAnalysis analyze(Long userId, Job job);
}

public interface GenerateEmailUseCase {
    EmailDraft generate(Long userId, Job job, JobAnalysis analysis);
}
```

### Domain models (persisted)

```java
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

public record EmailDraft(
    Long id,
    Long jobId,
    Long userId,
    String subject,
    String body,
    EmailStatus status,
    LocalDateTime generatedAt
) {}
```

---

## Error cases

| Situation | Exception | HTTP | Message (typical) |
|-----------|-----------|------|-------------------|
| Analyze without saved profile | `ProfileNotConfiguredException` | 400 | `Please configure your resume and skills profile first` |
| Generate email without prior analysis | `AnalysisNotFoundException` | 400 | `Job must be analyzed before generating an email draft` |
| Job id not found | `JobNotFoundException` | 404 | `Job not found with id: {id}` |
| Email draft not found for user+job | `JobNotFoundException` | 404 | `Email draft not found for job id: {id}` |
| Empty job description | `IllegalArgumentException` | 400 | `Job description must not be empty` |
| AI failure | `AiException` | 502 | (wrapped message) |

---

## Out of scope

- Re-running analysis automatically on `POST /email` (caller must analyze first)
- Including the user's display `name` in the email generation prompt (prompt uses resume + skills only)
- Upsert semantics on duplicate `POST /analyze` for the same user+job (second insert may fail on unique constraint until upsert is implemented)
