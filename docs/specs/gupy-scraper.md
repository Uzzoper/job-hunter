# Spec: Gupy Scraper

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.GupyScraper`
> **Corresponding test:** `GupyScraperTest.java`

---

## Context

Gupy exposes an unauthenticated JSON endpoint that returns job listings.
The scraper queries the endpoint and maps the results to domain `Job` objects.

**Base endpoint:**
```
GET https://portal.api.gupy.io/api/v1/jobs?jobName=desenvolvedor&limit=20
```

---

## Expected behavior

### Scenario 1: valid response with jobs
- **GIVEN** the Gupy endpoint returns JSON with a list of jobs
- **WHEN** `fetch()` is called
- **THEN** returns a list of correctly mapped `Job` objects
- **AND** the fields `title`, `company`, `url` and `postedAt` are populated

### Scenario 2: response with empty list
- **GIVEN** the endpoint returns JSON with `data: []`
- **WHEN** `fetch()` is called
- **THEN** returns an empty list without throwing an exception

### Scenario 3: request timeout
- **GIVEN** the endpoint does not respond within 5 seconds
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException` with a descriptive message

### Scenario 4: HTTP 4xx or 5xx response
- **GIVEN** the endpoint returns status 500
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException`

### Scenario 5: malformed JSON
- **GIVEN** the endpoint returns an invalid body
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException` — does not propagate `JsonParseException` directly

---

## Business rules

- The `description` field may be `null` in the response — use empty string as fallback
- `postedAt` comes as an ISO-8601 string (`"2025-03-10T14:00:00Z"`) — convert to `LocalDate`
- Filter only jobs whose title contains configurable keywords (`desenvolvedor`, `developer`, `engenheiro de software`)
- Keywords are case-insensitive
- The `Job` `id` is generated locally with `UUID.randomUUID()`

---

## Interface contract (port)

```java
// Implements the output port
public interface ScraperPort {
    List<Job> fetch();
}

// Configuration via application.properties
// scraper.gupy.keywords=desenvolvedor,developer,estagiário,engenheiro de software
// scraper.gupy.limit=20
// scraper.gupy.timeout-seconds=5
```

---

## Expected JSON response structure

```json
{
  "data": [
    {
      "id": 12345,
      "name": "Desenvolvedor Java Júnior",
      "company": { "name": "CompanyX" },
      "jobUrl": "https://company.gupy.io/jobs/12345",
      "publishedDate": "2025-03-10T14:00:00.000Z",
      "description": "We are looking for a developer..."
    }
  ]
}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Timeout | `ScraperException` | Message: "Gupy scraper timed out after 5s" |
| HTTP 4xx/5xx | `ScraperException` | Message includes status code |
| Invalid JSON | `ScraperException` | Wraps the original cause |

---

## Out of scope

- Does not authenticate — uses public endpoint
- Does not paginate beyond the configured `limit`
- Does not implement retry (can be added later with `@Retryable`)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/gupy-scraper.md.

Step 1 — write GupyScraperTest at
src/test/java/.../infrastructure/scraper/GupyScraperTest.java
Use WireMock to simulate HTTP responses.
Cover all scenarios in this spec. RED.

Step 2 — after my confirmation, implement GupyScraper
using RestClient. It must implement ScraperPort.

Step 3 — after my confirmation, refactor the JSON
mapping into a dedicated private method.
```
