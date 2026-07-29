# Spec: Filter jobs list by contact email presence

> **Layer:** `application` / `web`
> **Implementation:** `JobController`, `FetchJobsService`, `JobPersistenceAdapter`, `JobJpaRepository`
> **Tests:** `JobControllerTest`

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

---

## API

```
GET /api/jobs?hasEmail=true
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `hasEmail` | `boolean` | no | Filter by contact email presence. Omit for all jobs. |

---

## Data flow

```
GET /api/jobs?hasEmail=true
  → JobController.getAllJobs(@RequestParam(required = false) Boolean hasEmail)
    → FetchJobsService.findAll(Boolean hasEmail)
      → if hasEmail == null → jobRepository.findAll()
      → if hasEmail == true → jobRepository.findAllByContactEmailIsNotNull()
      → if hasEmail == false → jobRepository.findAllByContactEmailIsNull()
```

---

## Interface contract

```java
// JobRepository (outbound port) — new methods
List<Job> findAllByContactEmailIsNotNull();
List<Job> findAllByContactEmailIsNull();

// ListJobsUseCase (inbound port) — overload
List<Job> findAll(Boolean hasEmail);
```

---

## Error cases

| Situation | HTTP Status | Message |
|---|---|---|
| `hasEmail` is not a boolean | 400 | `"hasEmail must be a boolean value"` (Spring handles this automatically) |

---

## Out of scope

- Filtering by other job fields (keyword, company, etc.)
- Pagination
- Sorting
