# Spec: Fetch and save jobs

> **Layer:** `application`
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.FetchJobsService`
> **Corresponding test:** `FetchJobsServiceTest.java`

---

## Expected behavior

### Scenario 1: new jobs found
- **GIVEN** the scraper returns a list of jobs
- **WHEN** `fetchAndSave()` is called
- **THEN** each job that does not yet exist in the database is saved
- **AND** already existing jobs (same URL) are skipped

### Scenario 2: no new jobs
- **GIVEN** the scraper returns jobs that already exist in the database
- **WHEN** `fetchAndSave()` is called
- **THEN** no job is saved
- **AND** no exception is thrown

### Scenario 3: scraper fails
- **GIVEN** the scraper throws a `ScraperException`
- **WHEN** `fetchAndSave()` is called
- **THEN** the exception is propagated
- **AND** no corrupted job is saved

### Scenario 4: empty list returned
- **GIVEN** the scraper returns an empty list
- **WHEN** `fetchAndSave()` is called
- **THEN** no write operation is performed on the database

---

## Business rules

- URL is the deduplication key — two records with the same URL are considered the same job
- Jobs with `postedAt` older than 30 days must be discarded before saving
- `matchScore` starts as `null` — it is only populated after AI analysis
- The scraper is called via the `ScraperPort` interface — the service does not know the concrete implementation

---

## Interface contract (port)

```java
// Input port
public interface FetchJobsUseCase {
    void fetchAndSave();
}

// Output port — scraper
public interface ScraperPort {
    List<Job> fetch();
}

// Output port — persistence
public interface JobRepository {
    boolean existsByUrl(String url);
    Job save(Job job);
}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Scraper throws HTTP error | `ScraperException` | Propagates, nothing is saved |
| Database unavailable | `DataAccessException` (Spring) | Propagates naturally |

---

## Out of scope

- Does not analyze the job with AI (that is `analyze-job.md`)
- Does not send notifications
- Does not filter by keywords (that is the scraper's responsibility)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/fetch-jobs.md.

Step 1 — write FetchJobsServiceTest at
src/test/java/.../application/FetchJobsServiceTest.java
covering all scenarios in this spec.
Use Mockito to mock ScraperPort and JobRepository.
The test must fail (RED). Do not write the implementation.

Step 2 — after my confirmation, implement FetchJobsService
with the minimum code to make the tests pass (GREEN).

Step 3 — after my confirmation, refactor by extracting
private methods with semantic names (REFACTOR).
```
