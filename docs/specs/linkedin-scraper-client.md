# Spec: LinkedIn Scraper Client

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.client.LinkedInScraperClient`
> **Corresponding test:** `LinkedInScraperClientTest.java`

---

## Context

The LinkedIn Scraper Client is the Java bridge between the Spring Boot application and the Node.js/Playwright microservice (`linkedin-scraper`). It implements the existing `ExtractionStrategy` interface used by `ProviderRegistry`, allowing the LinkedIn scraper to be registered alongside Gupy and InfoJobs as a standard provider.

There are **two modes** controlled by `scraper.linkedin.mode`:

| Mode | Strategy | When to use |
|------|----------|-------------|
| `service` | `LinkedInScraperClient` via `RestClient` | Production — bypasses bot detection via Playwright |
| `jsoup` | `LinkedInProvider` via Jsoup | Fallback — lightweight, no Docker dependency |

The `service` mode is the default for production. The `jsoup` mode has `matchIfMissing=true` for backward compatibility in environments without the Playwright container.

---

## Architecture

```
Spring Boot App
│
├── ProviderRegistry
│   ├── GupyProvider
│   ├── InfoJobsProvider
│   └── LinkedInScraperClient ──▶ Node.js Service (:3000)
│       (implements ExtractionStrategy)
│
└── LinkedInScraperProperties
    (@ConfigurationProperties: scraper.linkedin.*)
```

### Data flow

```
FetchJobsService / POST /api/jobs/fetch
  → ProviderRegistry.fetch()
    → LinkedInScraperClient.extract()
      → GET http://linkedin-scraper:3000/api/jobs?keywords=...&location=...
      → Parse JSON response
      → Map to List<RawJob> with source="linkedin" and metadata
      → Return to ProviderRegistry
    → JobNormalizer.normalize() (via linkedinJobNormalizer bean)
  → JobRepository.saveAll()
```

---

## Interface contract

```java
// Existing output port reused by LinkedInScraperClient
public interface ExtractionStrategy {
    String providerId();
    List<RawJob> extract();
}

// LinkedInScraperClient also exposes:
public RawJob extractDetail(String jobId);
```

---

## Configuration

### `LinkedInScraperProperties`

```java
@ConfigurationProperties(prefix = "scraper.linkedin")
public record LinkedInScraperProperties(
    boolean enabled,
    String mode,              // "service" | "jsoup"
    String serviceUrl,        // Node.js service URL
    int timeoutSeconds,
    int connectTimeoutSeconds,
    int maxJobs,
    String baseUrl,           // https://www.linkedin.com
    List<String> keywords,
    String locations,
    List<String> geoIds,
    List<String> seniority,
    List<String> workType,
    String timeRange,
    int maxPages,
    long detailFetchDelayMillis
) {}
```

### application.yaml

```yaml
scraper:
  linkedin:
    enabled: true
    mode: service
    service-url: http://linkedin-scraper:3000   # Docker; overridden to localhost:3000 locally
    connect-timeout-seconds: 5
    base-url: https://www.linkedin.com
    keywords: "desenvolvedor junior,software engineer junior,backend junior,...,estagiario dev"
    locations: "Brazil,São Paulo,Rio de Janeiro,Curitiba,Remote"
    geo-ids: "106057199,102927786,105972731,105906364"
    seniority: "entry_level"
    work-type: "remote,hybrid,on-site"
    time-range: "past_week"
    max-jobs: 25
    timeout-seconds: 30
    detail-fetch-delay-millis: 500
```

### Local override (application-local.yaml)

```yaml
scraper:
  linkedin:
    service-url: http://localhost:3000
```

---

## Dual-mode wiring (AppConfig)

```java
// Jsoup mode (fallback, matchIfMissing)
@Bean
@Qualifier("linkedinProvider")
@ConditionalOnProperty(name = "scraper.linkedin.mode", havingValue = "jsoup", matchIfMissing = true)
public ExtractionStrategy linkedinProvider(...) {
    return new LinkedInProvider(...);
}

// Service mode (production)
@Bean
@Qualifier("linkedinScraperClient")
@ConditionalOnProperty(name = "scraper.linkedin.mode", havingValue = "service")
public ExtractionStrategy linkedinScraperClient(LinkedInScraperProperties props) {
    return new LinkedInScraperClient(props);
}

// Both registered as Optional in ProviderRegistry
linkedinProvider.ifPresent(p -> registry.register(p, retry, rateLimiter, linkedinJobNormalizer));
linkedinScraperClient.ifPresent(p -> registry.register(p, retry, rateLimiter, linkedinJobNormalizer));
```

---

## Expected behavior

### Scenario 1: valid search response (service mode)
- **GIVEN** the Node.js service returns a valid JSON response with jobs
- **WHEN** `extract()` is called
- **THEN** returns a list of `RawJob` objects with `source="linkedin"`
- **AND** each job has `title`, `company`, `url` (`https://www.linkedin.com/jobs/view/{id}`), `rawDate`, `location` populated
- **AND** `metadata["jobId"]` contains the LinkedIn numeric job ID
- **AND** `metadata` may include `requirements`, `jobType`, `seniority`, `salary`

### Scenario 2: empty search response
- **GIVEN** the Node.js service returns `{"success": true, "data": []}`
- **WHEN** `extract()` is called
- **THEN** returns an empty list without throwing an exception

### Scenario 3: HTTP 429 rate limited
- **GIVEN** the Node.js service returns HTTP 429
- **WHEN** `extract()` is called
- **THEN** throws `ScraperException` with a message containing "429" or "RATE_LIMITED"

### Scenario 4: HTTP 503 service unavailable
- **GIVEN** the Node.js service returns HTTP 503 (browser down)
- **WHEN** `extract()` is called
- **THEN** throws `ScraperException`

### Scenario 5: connection timeout
- **GIVEN** the Node.js service does not respond within `connectTimeoutSeconds`
- **WHEN** `extract()` is called
- **THEN** throws `ScraperException`

### Scenario 6: successful detail fetch
- **GIVEN** a valid LinkedIn job ID
- **WHEN** `extractDetail("12345")` is called
- **THEN** returns a `RawJob` with `description` populated
- **AND** `source="linkedin"`, `title`, `company`, `url` are populated
- **AND** `metadata` includes `jobId`

### Scenario 7: detail endpoint 404
- **GIVEN** an invalid or login-walled LinkedIn job ID
- **WHEN** `extractDetail("99999")` is called
- **THEN** throws `ScraperException` with a message containing "404"

### Scenario 8: providerId
- **GIVEN** the client is instantiated
- **WHEN** `providerId()` is called
- **THEN** returns `"linkedin"`

---

## Error handling

| Situation | HTTP from service | Exception | Expected behavior |
|-----------|-------------------|-----------|-------------------|
| Rate limited | 429 | `ScraperException` | Message: "linkedin rate limited (429): ..." |
| Service unavailable | 503 | `ScraperException` | Message: "linkedin service unavailable (503): ..." |
| Not found | 404 | `ScraperException` | Message: "linkedin not found (404): ..." |
| Other HTTP error | 4xx/5xx | `ScraperException` | Message includes HTTP status |
| Timeout | — | `ScraperException` | Wraps the original cause |
| Empty response body | — | `ScraperException` | Message: "returned empty response" |
| JSON `success=false` | 200 | `ScraperException` | Includes error code + message from Node.js |

---

## RawJob mapping

| JSON field | RawJob field | Notes |
|------------|--------------|-------|
| `id` | `metadata["jobId"]` | Numeric LinkedIn job ID |
| `title` | `title` | |
| `company` | `company` | |
| `location` | `location` | |
| `postedAt` | `rawDate` | ISO-8601 string |
| `description` | `description` | HTML string from detail endpoint |
| `requirements` | `metadata["requirements"]` | Joined with `; ` |
| `jobType` | `metadata["jobType"]` | |
| `seniority` | `metadata["seniority"]` | |
| `salary` | `metadata["salary"]` | |
| — | `url` | Built as `https://www.linkedin.com/jobs/view/{id}` |
| — | `source` | Hardcoded to `"linkedin"` |

---

## Business rules

- Uses `RestClient` with `SimpleClientHttpRequestFactory` for HTTP (not WebClient)
- Timeouts are set separately for connect (5s default) and read (30s default)
- Empty or null JSON `data` array → return empty list (no exception)
- Individual job mapping failures → log warning and skip (resilient)
- `maxJobs` limits the result list size after mapping
- First keyword and first location from config are used for search
- Error responses from Node.js are parsed and wrapped in `ScraperException`

---

## Out of scope

- Does not handle authentication — Node.js service uses guest access
- Does not implement retry or circuit breaker (inherits from `ProviderRegistry` / `ExponentialBackoffRetry`)
- Does not validate keyword or location inputs (passed through to Node.js service)
- Does not parse the HTML description (returned as raw HTML for AI analysis)

---

## Test Coverage

| Test class | File | Scenarios | Tools |
|------------|------|-----------|-------|
| `LinkedInScraperClientTest` | `.../client/LinkedInScraperClientTest.java` | 8 scenarios | JUnit 5 + WireMock |

**Scenarios covered:**
1. Valid search response → mapped `RawJob` list with `source=linkedin`
2. Empty search response → empty list
3. HTTP 429 → `ScraperException`
4. HTTP 503 → `ScraperException`
5. Connection timeout → `ScraperException`
6. Detail endpoint → `RawJob` with description
7. Detail HTTP 404 → `ScraperException`
8. `providerId()` → `"linkedin"`

Test method naming: `methodName_scenario_expectedResult()`, e.g.:
- `extract_whenValidResponse_shouldReturnRawJobs()`
- `extract_whenServiceReturns429_shouldThrowScraperException()`

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/linkedin-scraper-client.md.

LinkedInScraperClient implements ExtractionStrategy and calls the Node.js
Playwright microservice via RestClient. It's configured via
scraper.linkedin.mode=service (production) or jsoup (fallback).

Key files:
- infrastructure/scraper/client/LinkedInScraperClient.java
- infrastructure/config/LinkedInScraperProperties.java
- infrastructure/config/AppConfig.java (dual-mode @Bean wiring)

Step 1 — understand the RawJob mapping and metadata extraction.
Step 2 — understand the error handling (HTTP status → ScraperException mapping).
Step 3 — run `./mvnw test -pl . -Dtest=LinkedInScraperClientTest` to verify.
```
