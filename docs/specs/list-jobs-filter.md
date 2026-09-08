# Spec: Filter jobs list by contact email presence + per-job draft status

> **Layer:** `application` / `web`
> **Implementation:** `JobController`, `FetchJobsService`, `JobPersistenceAdapter`, `JobJpaRepository`, `JobResponse`, `JobWithDraftStatus`
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

---

## API

```
GET /api/jobs?hasEmail=true&excludeApplied=true
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `hasEmail` | `boolean` | no | Filter by contact email presence. Omit for all jobs. |
| `excludeApplied` | `boolean` | no | Drop jobs the current user already applied to (SENT draft for the `(jobId, userId)` pair). Omit to keep them. |

Response list items gain a `draftStatus` field (`null` when the user has no
draft; the draft `EmailStatus` otherwise). The field is also present on
`GET /api/jobs/{id}` as `null` (detail endpoint does not resolve it).

## Data flow

```
GET /api/jobs?hasEmail=true&excludeApplied=true
  → JobController.getAllJobs(@RequestParam(required = false) Boolean hasEmail,
                             @RequestParam(required = false) Boolean excludeApplied)
    → currentUserService.getCurrentUserId()
    → FetchJobsService.findAllWithDraftStatus(userId, hasEmail, excludeApplied)
      → list = findAll(hasEmail)                 // existing repository filter
      → for each job → emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                       → draftStatus = draft.status() | null (no draft)
      → if excludeApplied == true → drop entries with draftStatus == SENT
    → JobController maps JobWithDraftStatus → JobResponse (job fields + draftStatus)
```

## Interface contract

```java
// JobRepository (outbound port) — unchanged, existing methods
List<Job> findAllByContactEmailIsNotNull();
List<Job> findAllByContactEmailIsNull();

// EmailDraftRepository (outbound port) — unchanged, existing method reused
Optional<EmailDraft> findByJobIdAndUserId(Long jobId, Long userId);

// application/port/in/JobWithDraftStatus — new listing value object
record JobWithDraftStatus(Job job, EmailStatus draftStatus)

// ListJobsUseCase (inbound port) — new method (existing methods unchanged)
List<JobWithDraftStatus> findAllWithDraftStatus(Long userId, Boolean hasEmail, Boolean excludeApplied);

// web/dto/JobResponse — gains one component
EmailStatus draftStatus   // nullable; null = no draft for the current user
```

---

## Error cases

| Situation | HTTP Status | Message |
|---|---|---|
| `hasEmail` is not a boolean | 400 | `"hasEmail must be a boolean value"` (Spring handles this automatically) |
| `excludeApplied` is not a boolean | 400 | `"excludeApplied must be a boolean value"` (Spring handles this automatically) |

---

## Out of scope

- Filtering by other job fields (keyword, company, etc.)
- Pagination
- Sorting
- Resolving `draftStatus` on the job **detail** endpoint (`GET /api/jobs/{id}`) — it is always `null` there
