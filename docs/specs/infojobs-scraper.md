# Spec: InfoJobs Scraper

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.InfoJobsScraper`
> **Corresponding test:** `InfoJobsScraperTest.java`

---

## Context

Gupy is already implemented as the primary source. InfoJobs is the next experimental source because it has public job search pages and relevant Brazilian listings for technology roles, but the quality of results is mixed.

The first implementation must validate whether InfoJobs adds useful junior developer opportunities without introducing risky behavior.

---

## Expected behavior

### Scenario 1: scraper disabled
- **GIVEN** `scraper.infojobs.enabled=false`
- **WHEN** `fetch()` is called
- **THEN** returns an empty list
- **AND** no HTTP request is made

### Scenario 2: valid search page with jobs
- **GIVEN** InfoJobs returns an HTML page containing job cards
- **WHEN** `fetch()` is called
- **THEN** returns mapped `Job` objects
- **AND** each job has `title`, `company`, `url`, `description`, `postedAt` and `matchScore=Optional.empty()`
- **AND** relative URLs are normalized to absolute URLs

### Scenario 3: empty search page
- **GIVEN** the search page contains no job cards
- **WHEN** `fetch()` is called
- **THEN** returns an empty list without throwing an exception

### Scenario 4: duplicate jobs
- **GIVEN** the same job appears for multiple keywords
- **WHEN** `fetch()` is called
- **THEN** returns only one job for that URL

### Scenario 5: excluded seniority
- **GIVEN** a job title contains excluded terms such as `senior`, `sênior`, `sr`, `pleno`, `lead` or `especialista`
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 6: non-developer business roles
- **GIVEN** a job title contains business-development terms such as `BDR` or `desenvolvedor de negócios`
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 7: location filtering
- **GIVEN** a job is not remote
- **AND** its visible location does not match configured locations
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 8: recent remote job
- **GIVEN** a job is marked as `Home office`, `Remoto` or located in `Todo Brasil`
- **WHEN** `fetch()` is called
- **THEN** the job is accepted if the title and age filters pass

### Scenario 9: old job listing
- **GIVEN** a job was posted more than `scraper.infojobs.max-age-days` ago
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 10: HTTP error, timeout or bot challenge
- **GIVEN** InfoJobs returns HTTP 403, HTTP 429, HTTP 5xx, a bot challenge, or the request times out
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException`
- **AND** the implementation does not attempt to bypass protections

### Scenario 11: malformed or incomplete job card
- **GIVEN** a card is missing title, URL or posted date data
- **WHEN** `fetch()` is called
- **THEN** skips that card
- **AND** continues mapping the remaining cards

---

## Business rules

- `InfoJobsScraper` implements the existing `ScraperPort`.
- `Job` identity remains URL-based.
- `id` is always `null` until persistence assigns a `Long`.
- `matchScore` is always `Optional.empty()` before AI analysis.
- `postedAt` must never be `null`.
- Default `max-age-days` is `30`, matching the domain expiration rule.
- Keyword matching is case-insensitive.
- Exclusion matching uses case-insensitive regex with word boundaries.
- Business-development false positives must be excluded by default (`bdr`, `desenvolvedor de negócios`, `desenvolvimento de negócios`, `business development`).
- Location matching accepts remote jobs or jobs whose visible location contains a configured location.
- The scraper must use `RestClient` for HTTP and Jsoup for HTML parsing.
- The scraper must not use login, browser automation, CAPTCHA solving, proxy rotation, private endpoints or bypass techniques.
- The scraper must stop on HTTP 403, HTTP 429 or bot-challenge pages.
- The scraper must apply a configurable delay between requests when more than one keyword/page is fetched.

---

## Interface contract

```java
// Existing output port
public interface ScraperPort {
    List<Job> fetch();
}
```

Suggested configuration:

```yaml
scraper:
  infojobs:
    enabled: true
    base-url: https://www.infojobs.com.br
    keywords: desenvolvedor junior,desenvolvedor júnior,analista desenvolvedor junior,programador junior,java junior,backend junior
    locations: Remoto,Home office,Todo Brasil,Ponta Grossa,Paraná,PR,Curitiba
    exclude-keywords: senior,sênior,sr,sr.,pleno,pl.,lead,especialista,bdr,desenvolvedor de negócios,desenvolvimento de negócios,business development
    max-pages: 1
    max-age-days: 30
    timeout-seconds: 10
    delay-millis: 2000
```

---

## Architecture notes

The current `FetchJobsService` receives one `ScraperPort`. To keep the application layer unaware of concrete sources, add:

```java
public class CompositeScraper implements ScraperPort
```

`CompositeScraper` belongs to `infrastructure.scraper`, receives a list of concrete scrapers, calls each one, deduplicates by URL, and returns the combined result.

If one scraper fails with `ScraperException`, `CompositeScraper` logs the failure and continues with the remaining sources. It throws `ScraperException` only when all configured scrapers fail.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Scraper disabled | none | Return empty list and make no HTTP request |
| HTTP 403 | `ScraperException` | Stop immediately |
| HTTP 429 | `ScraperException` | Stop immediately |
| HTTP 5xx | `ScraperException` | Stop current fetch |
| CAPTCHA/bot challenge HTML | `ScraperException` | Stop immediately |
| Timeout | `ScraperException` | Message includes timeout seconds |
| Unexpected HTML card | none for that card | Skip card and continue |

---

## Out of scope

- Does not authenticate.
- Does not apply to jobs automatically.
- Does not scrape resumes, company reviews, salaries or candidate data.
- Does not bypass bot protections, CAPTCHA, login, rate limits or security controls.
- Does not fetch detail pages in the first version; it uses list-card snippets.

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/infojobs-scraper.md.

Step 1 — write InfoJobsScraperTest at
src/test/java/com/juanperuzzo/job_hunter/unit/infrastructure/scraper/InfoJobsScraperTest.java.
Use WireMock HTML fixtures. Cover all scenarios in this spec.
The tests must fail (RED).

Step 2 — implement InfoJobsScraper using RestClient and Jsoup.
It must implement ScraperPort.

Step 3 — add CompositeScraper so Gupy and InfoJobs can coexist
behind a single ScraperPort.

Step 4 — after GREEN, refactor parser and filtering methods
without changing behavior.
```
