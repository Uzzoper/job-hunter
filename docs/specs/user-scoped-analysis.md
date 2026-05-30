# Spec: User-Scoped Job Analysis & Email Generation

> **Layer:** `application` | `domain` | `web`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.application.service.AiAnalysisService`
> - `com.juanperuzzo.job_hunter.application.service.EmailGenerationService`
> - `com.juanperuzzo.job_hunter.web.controller.JobController`
> **Corresponding tests:** `AiAnalysisServiceTest.java`, `EmailGenerationServiceTest.java`

---

## Expected behavior

The job analysis and personalized email draft must be scoped to the authenticated user's profile data (CV resume and hardskills list).

### Scenario 1: Run User-Scoped AI Analysis
- **GIVEN** an authenticated user who has populated their profile CV and skills
- **WHEN** they call `POST /api/jobs/{id}/analyze`
- **THEN** the system fetches the job description and the user's profile CV
- **AND** sends both to the AI client (OpenRouter) using the prompts documented in `docs/prompts.md`
- **AND** saves the analysis results (score, matched skills, missing skills, tone, and summary) in the database linked to the `job_id` and `user_id`
- **AND** returns HTTP 200 OK with the analysis details.

### Scenario 2: Generate Email Draft for User
- **GIVEN** a job that has been analyzed for the active user
- **WHEN** the user calls `POST /api/jobs/{id}/email`
- **THEN** the system uses the user's name, profile CV, and the job analysis results to generate a personalized application email
- **AND** saves the draft in the database linked to the `job_id` and `user_id`
- **AND** returns HTTP 200 OK with the email details.

---

## Business rules

- **Score Calculation:** The match score must evaluate how well the user's profile matches the job requirements.
- **Data Isolation:** User A must never see or overwrite the `matchScore`, analysis, or email drafts of User B. Every analysis query and email query must filter by the logged-in user's ID.
- **Skills Mapping:** The system compares the job requirements with the `skills` array saved in the user's profile to define `matchedSkills` and `missingSkills`.

---

## Interface contract

The API endpoints in `JobController` will be updated:
- `POST /api/jobs/{id}/analyze` -> Returns `JobAnalysis`
- `GET /api/jobs/{id}/email` -> Returns `EmailDraftResponse`
- `POST /api/jobs/{id}/email` -> Returns `EmailDraftResponse`

All endpoints will retrieve the active user's ID from the Spring Security context to load their profile.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Analyze job before configuring profile | `ProfileNotConfiguredException` | Return HTTP 400 Bad Request with message "Please configure your resume and skills profile first" |
| Generate email before running analysis | `AnalysisNotFoundException` | Return HTTP 400 Bad Request with message "Job must be analyzed before generating an email draft" |
