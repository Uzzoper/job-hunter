# Spec: Filter jobs list by contact email presence + per-job draft status + matchScore

> **Layer:** `application` / `web`
> **Implementation:** `JobController`, `FetchJobsService`, `JobPersistenceAdapter`, `JobJpaRepository`, `JobResponse`, `JobWithDraftStatus`, `JobAnalysisRepository`
> **Tests:** `JobControllerTest`, `FetchJobsServiceTest`

---

## Expected behavior

### Scenario 1: no filter (default)
- **GIVEN** `GET /api/jobs` without `hasEmail` param
- **WHEN** the endpoint is called
- **THEN** returns all jobs (same behavior as today)

### Scenario 2: `hasEmail=true`
- **GIVEN** `GET /api/jobs?hasEmail=true`
- **WHEN** the endpoint is called
- **THEN** returns only jobs where `contact_email` is not null

### Scenario 3: `hasEmail=false`
- **GIVEN** `GET /api/jobs?hasEmail=false`
- **WHEN** the endpoint is called
- **THEN** returns only jobs where `contact_email` is null

### Scenario 4: list always carries the current user's draft status
- **GIVEN** `GET /api/jobs` authenticated as a user
- **WHEN** the endpoint is called
- **THEN** every job in the response carries a `draftStatus` field resolved for
  `(jobId, userId)` via the existing `EmailDraftRepository.findByJobIdAndUserId`:
  - `SENT` when the user has a SENT draft for the job — i.e. the user **already applied**
  - the draft's status (`PENDING` / `APPROVED` / `REJECTED`) when a non-sent draft exists
  - `null` when the user has no draft for the job
- No new table, no migration, no new repository method — the existing port
  lookups are reused (see N+1 note below).

### Scenario 5: `excludeApplied=true`
- **GIVEN** `GET /api/jobs?excludeApplied=true`
- **WHEN** the endpoint is called
- **THEN** jobs whose `(jobId, userId)` draft has status `SENT` are **dropped**;
  jobs with `PENDING` / `APPROVED` / `REJECTED` drafts or no draft are kept

### Scenario 6: `excludeApplied=false` or omitted
- **GIVEN** `GET /api/jobs?excludeApplied=false` or the param omitted
- **WHEN** the endpoint is called
- **THEN** no job is dropped — already-applied jobs stay in the list, marked `SENT`

### Scenario 7: matchScore join (server-side)
- **GIVEN** `GET /api/jobs` authenticated as a user
- **WHEN** the endpoint is called
- **THEN** every job in the response carries a `matchScore` field resolved for
  `(jobId, userId)` via the existing `JobAnalysisRepository.findByJobIdAndUserId`:
  - the analysis's `matchScore` (0–100) when the user has analyzed the job
  - `null` when the user has not analyzed the job
- Results are sorted server-side by `matchScore` descending; jobs with
  `null` score (unanalyzed) sort **last**, below all scored jobs.

### Scenario 8: `minScore` server-side filter
- **GIVEN** `GET /api/jobs?minScore=60`
- **WHEN** the endpoint is called
- **THEN** only jobs whose user-scoped `matchScore >= minScore` are returned;
  jobs with `null` matchScore (unanalyzed) are **always dropped** when
  `minScore` is specified (an unanalyzed job has no score to compare).
- When `minScore` is omitted or `null`, no score filtering occurs.

### Scenario 9: `minScore` combined with other filters
- **GIVEN** `GET /api/jobs?minScore=60&hasEmail=true&excludeApplied=true`
- **WHEN** the endpoint is called
- **THEN** filters are applied in this order:
  1. `hasEmail` (base query selection)
  2. `excludeApplied` (drop SENT-draft jobs)
  3. `minScore` (drop below-threshold / unanalyzed jobs)
- Final list is sorted by `matchScore` descending (nulls last).

### User scoping
- Draft lookups are always scoped to the current authenticated user via
  `CurrentUserService` (`userId`), exactly like the other user-scoped endpoints
  (`/api/jobs/{id}/email`, `/api/jobs/{id}/approve`, `/api/jobs/{id}/send`, ...).
- A `SENT` draft owned by **another user** never marks a job as applied and never
  triggers exclusion.

### N+1 note (accepted)
- Resolving `draftStatus` triggers one `findByJobIdAndUserId` call per listed job
  (1 + N queries). At the current job-list scale this is negligible and keeps the
  change reuse-only (no new repository method). If the list grows, replace with a
  single bulk `findByUserIdAndStatusIn(userId, ...)` lookup keyed by `jobId`.
- Resolving `matchScore` triggers one additional `findByJobIdAndUserId` call per
  listed job on `JobAnalysisRepository` (same N+1 pattern). The two lookups
  (draft + analysis) are independent and can be collapsed into a single per-job
  helper in the future.

---

## API

```
GET /api/jobs?hasEmail=true&excludeApplied=true&minScore=60
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `hasEmail` | `boolean` | no | Filter by contact email presence. Omit for all jobs. |
| `excludeApplied` | `boolean` | no | Drop jobs the current user already applied to (SENT draft for the `(jobId, userId)` pair). Omit to keep them. |
| `minScore` | `integer` | no | Drop jobs whose user-scoped matchScore is below this threshold. Unanalyzed jobs (null score) are also dropped when specified. Omit to include all. |

Response list items gain:
- `draftStatus` field (`null` when the user has no draft; the draft `EmailStatus` otherwise).
- `matchScore` field (`null` when the user has not analyzed the job; the score 0–100 otherwise).
The fields are also present on `GET /api/jobs/{id}` as `null` (detail endpoint does not resolve them).

## Data flow

```
GET /api/jobs?hasEmail=true&excludeApplied=true&minScore=60
  → JobController.getAllJobs(@RequestParam(required = false) Boolean hasEmail,
                             @RequestParam(required = false) Boolean excludeApplied,
                             @RequestParam(required = false) Integer minScore)
    → currentUserService.getCurrentUserId()
    → FetchJobsService.findAllWithDraftStatus(userId, hasEmail, excludeApplied, minScore)
      → list = findAll(hasEmail)                 // existing repository filter
      → for each job:
          → emailDraftRepository.findByJobIdAndUserId(jobId, userId)  → draftStatus
          → jobAnalysisRepository.findByJobIdAndUserId(jobId, userId) → matchScore
      → if excludeApplied == true → drop entries with draftStatus == SENT
      → if minScore != null → drop entries where matchScore is null or matchScore < minScore
      → sort: scored jobs descending by matchScore, unanalyzed (null) last
    → JobController maps JobWithScore → JobResponse (job fields + draftStatus + matchScore)
```

## Interface contract

```java
// JobRepository (outbound port) — unchanged, existing methods
List<Job> findAllByContactEmailIsNotNull();
List<Job> findAllByContactEmailIsNull();

// EmailDraftRepository (outbound port) — unchanged, existing method reused
Optional<EmailDraft> findByJobIdAndUserId(Long jobId, Long userId);

// JobAnalysisRepository (outbound port) — unchanged, existing method reused
Optional<JobAnalysis> findByJobIdAndUserId(Long jobId, Long userId);

// application/port/in/JobWithDraftStatus — extended value object
record JobWithDraftStatus(Job job, EmailStatus draftStatus, Integer matchScore)

// ListJobsUseCase (inbound port) — extended signature
List<JobWithDraftStatus> findAllWithDraftStatus(Long userId, Boolean hasEmail, Boolean excludeApplied, Integer minScore);

// web/dto/JobResponse — gains one component
Integer matchScore   // nullable; null = no analysis for the current user
```

---

## Error cases

| Situation | HTTP Status | Message |
|---|---|---|
| `hasEmail` is not a boolean | 400 | `"hasEmail must be a boolean value"` (Spring handles this automatically) |
| `excludeApplied` is not a boolean | 400 | `"excludeApplied must be a boolean value"` (Spring handles this automatically) |
| `minScore` is not an integer | 400 | `"minScore must be an integer value"` (Spring handles this automatically) |

---

## Out of scope

- Filtering by other job fields (keyword, company, etc.)
- Pagination
- Resolving `matchScore` on the job **detail** endpoint (`GET /api/jobs/{id}`) — it is always `null` there
