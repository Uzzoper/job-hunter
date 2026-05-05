# Spec: Indeed Scraper

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.IndeedScraper`
> **Corresponding test:** `IndeedScraperTest.java`

---

## Research summary

Research date: 2026-05-04.

Indeed is not like Gupy for this project. Gupy exposes a public unauthenticated JSON endpoint; Indeed primarily exposes HTML search pages and partner-oriented APIs.

Important findings:

- Indeed's current Terms of Service, last updated on 2026-04-14, prohibit automated systems such as bots, scrapers and spiders from accessing, data-mining, crawling or extracting content without express written permission.
- The official Indeed Partner Docs describe APIs for managing jobs, employers and candidates, with OAuth and Partner Console access. They do not document an open backend API for arbitrary public job search harvesting.
- Indeed's Job Postings documentation says publishing partners should use the Publisher JavaScript Plugin for front-end display only, while ATS partners use Job Sync API and agencies use Job Update API.
- Public robots data for `indeed.com` shows many job/search/detail paths disallowed for generic crawlers, including job-related paths and `/viewjob?`.

Sources:

- https://www.indeed.com/legal?hl=en_US
- https://docs.indeed.com/
- https://docs.indeed.com/getstarted/
- https://docs.indeed.com/job-postings/
- https://well-known.dev/resources/robots_txt/sites/indeed.com

## Decision

Do not implement live Indeed scraping as an enabled production feature by default.

The recommended implementation path is:

1. Build a conservative `IndeedScraper` that can parse Indeed-like HTML fixtures and WireMock responses.
2. Keep live access disabled unless the project owner explicitly confirms permission/risk acceptance in configuration.
3. Never bypass bot protections, login walls, CAPTCHAs, rate limits or security controls.
4. Prefer an official or licensed integration if the goal becomes production-scale Indeed ingestion.

This spec deliberately keeps the architecture ready for Indeed, but prevents accidental non-compliant crawling.

---

## Expected behavior

### Scenario 1: live scraping disabled
- **GIVEN** `scraper.indeed.enabled=false`
- **WHEN** `fetch()` is called
- **THEN** returns an empty list
- **AND** no HTTP request is made

### Scenario 2: live scraping enabled without compliance confirmation
- **GIVEN** `scraper.indeed.enabled=true`
- **AND** `scraper.indeed.compliance-confirmed=false`
- **WHEN** the scraper is created or `fetch()` is called
- **THEN** throws `ScraperException`
- **AND** the message explains that live Indeed access requires explicit permission/risk acceptance

### Scenario 3: valid search page with jobs
- **GIVEN** WireMock returns an HTML search page containing job cards
- **AND** compliance confirmation is enabled for the test instance
- **WHEN** `fetch()` is called
- **THEN** returns mapped `Job` objects
- **AND** each job has `title`, `company`, `url`, `description`, `postedAt` and `matchScore=Optional.empty()`
- **AND** relative Indeed URLs are normalized to absolute URLs

### Scenario 4: valid page with duplicate jobs
- **GIVEN** the same job appears for multiple keywords or pages
- **WHEN** `fetch()` is called
- **THEN** returns only one `Job` for that URL

### Scenario 5: empty search page
- **GIVEN** the search page contains no job cards
- **WHEN** `fetch()` is called
- **THEN** returns an empty list without throwing an exception

### Scenario 6: filtered job title
- **GIVEN** a job title contains excluded terms such as `senior`, `pleno`, `sr`, `lead` or `especialista`
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 7: location filtering
- **GIVEN** a job is not remote
- **AND** its location does not match configured locations
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 8: old job listing
- **GIVEN** a job was posted more than `scraper.indeed.max-age-days` ago
- **WHEN** `fetch()` is called
- **THEN** that job is discarded

### Scenario 9: HTTP 403, 429 or bot challenge
- **GIVEN** Indeed returns HTTP 403, HTTP 429, or an HTML page that looks like a bot challenge/CAPTCHA
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException`
- **AND** the implementation must not retry aggressively or attempt bypass techniques

### Scenario 10: timeout or network failure
- **GIVEN** the request times out or the server is unavailable
- **WHEN** `fetch()` is called
- **THEN** throws `ScraperException` with a descriptive message

### Scenario 11: malformed or unexpected HTML
- **GIVEN** the response is valid HTML but does not match expected page structure
- **WHEN** `fetch()` is called
- **THEN** returns the jobs it can safely map
- **AND** skips incomplete cards
- **AND** logs mapping failures without exposing stack traces through HTTP

---

## Business rules

- `IndeedScraper` implements the existing `ScraperPort`.
- `Job` identity remains URL-based.
- `id` is always `null` until persistence assigns a `Long`.
- `matchScore` is always `Optional.empty()` before AI analysis.
- `postedAt` must never be `null`, because the domain model rejects null dates.
- If Indeed does not expose an exact publication date, parse relative Portuguese dates such as `há 3 dias`; if no date can be parsed, use `LocalDate.now()` and log a warning.
- Default `max-age-days` is `30`, matching the domain expiration rule.
- Keyword matching is case-insensitive.
- Exclusion matching uses case-insensitive regex with word boundaries.
- Location matching accepts jobs marked as remote or jobs whose visible location contains a configured location.
- The scraper must use `RestClient` for HTTP and Jsoup for HTML parsing.
- The scraper must use an honest, configurable user agent identifying the application; it must not pretend to be a normal browser for evasion.
- The scraper must not use browser automation, proxy rotation, CAPTCHA solving, credentialed sessions, private endpoints or reverse-engineered GraphQL calls.
- The scraper must apply a configurable delay between live requests.
- The scraper must stop on 403, 429, CAPTCHA or bot-challenge pages.

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
  indeed:
    enabled: false
    compliance-confirmed: false
    base-url: https://br.indeed.com
    keywords: desenvolvedor,developer,backend,java
    locations: Remoto,Ponta Grossa,Paraná,PR
    exclude-keywords: senior,sênior,sr,pleno,lead,especialista
    max-pages: 1
    page-size: 10
    max-age-days: 30
    delay-millis: 3000
    timeout-seconds: 10
    user-agent: JobHunter/1.0 (+local development; contact: configured-by-owner)
```

Configuration rules:

- `enabled=false` means no HTTP request.
- `enabled=true` and `compliance-confirmed=false` means fail fast.
- Tests may set both `enabled=true` and `compliance-confirmed=true` against WireMock only.

---

## Architecture notes

The current `FetchJobsService` receives a single `ScraperPort`. Adding Gupy plus Indeed will require one of these approaches:

1. **Recommended:** create a `CompositeScraper` in `infrastructure.scraper` that implements `ScraperPort`, receives enabled scraper implementations, calls each one, deduplicates by URL, and returns a combined list.
2. **Alternative:** change `FetchJobsService` to receive `List<ScraperPort>` and deduplicate there.

Recommendation: use `CompositeScraper`, because orchestration of external sources is infrastructure detail and keeps the application service simple.

---

## Parsing strategy

The parser should be isolated in private methods so tests can cover mapping behavior through WireMock HTML fixtures.

Minimum fields:

| Domain field | Source |
|---|---|
| `title` | Job card title text |
| `company` | Job card company text |
| `url` | Job detail link, normalized to absolute URL |
| `description` | Card snippet or detail page body when safely available |
| `postedAt` | Parsed relative/absolute posted date, fallback to today |
| `matchScore` | `Optional.empty()` |

Implementation preference:

- Search/list page parsing only for the first iteration.
- Detail page fetching is optional and should be off by default to reduce request volume.
- If detail fetching is later enabled, it must respect the same delay, timeout and stop conditions.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Enabled without compliance confirmation | `ScraperException` | Fail fast, no HTTP request |
| HTTP 403 | `ScraperException` | Stop immediately |
| HTTP 429 | `ScraperException` | Stop immediately, no aggressive retry |
| CAPTCHA/bot challenge HTML | `ScraperException` | Stop immediately |
| Timeout | `ScraperException` | Message includes timeout seconds |
| Network failure | `ScraperException` | Wrap original cause |
| Unexpected HTML card | none for that card | Skip card and continue |

---

## Out of scope

- Does not bypass bot protections, CAPTCHA, login, rate limits or Cloudflare-style challenges.
- Does not use proxy services, residential IPs, browser fingerprinting or user-agent rotation.
- Does not submit applications through Indeed.
- Does not scrape resumes, profiles, company reviews or salary pages.
- Does not implement an official Indeed Partner API integration.
- Does not persist source-specific metadata beyond the existing `Job` model.

---

## Implementation plan

### Step 1: tests first

Create `IndeedScraperTest` using JUnit 5 and WireMock.

Cover:

- disabled scraper performs no request and returns empty list
- enabled without compliance confirmation fails fast
- valid HTML maps jobs
- duplicate URLs are deduplicated
- excluded senior/pleno jobs are filtered
- non-matching location jobs are filtered
- old jobs are filtered
- 403, 429, timeout and bot-challenge responses throw `ScraperException`
- malformed/unexpected cards are skipped

### Step 2: minimum implementation

Implement `IndeedScraper` with:

- constructor-based configuration
- `RestClient`
- Jsoup parsing
- local filtering methods similar to `GupyScraper`
- no live request when disabled or not confirmed

### Step 3: integration with multiple scrapers

Add `CompositeScraper` and configure `FetchJobsService` to receive one composite `ScraperPort`.

### Step 4: refactor

Extract small private methods:

- `buildSearchUri`
- `parseJobs`
- `mapCardToJob`
- `parsePostedAt`
- `matchesKeywords`
- `isExcluded`
- `matchesLocation`
- `isBotChallenge`

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/indeed-scraper.md.

Step 1 — write IndeedScraperTest at
src/test/java/com/juanperuzzo/job_hunter/unit/infrastructure/scraper/IndeedScraperTest.java.
Use WireMock HTML fixtures. Cover all scenarios in this spec.
The tests must fail (RED). Do not write production code yet.

Step 2 — after my confirmation, implement IndeedScraper
using RestClient and Jsoup. It must implement ScraperPort.

Step 3 — after my confirmation, add CompositeScraper so Gupy
and Indeed can coexist behind a single ScraperPort.

Step 4 — after GREEN, refactor parser and filtering methods
without changing behavior.
```
