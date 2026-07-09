# Provider-Based Scraping Architecture

> Architecture document for the provider-scraping-migration, replacing flat `ScraperPort` implementations with a provider-based architecture with shared retry, rate limiting, and normalization pipeline.

## Overview

The original scraping layer had each job board (`GupyScraper`, `InfoJobsScraper`) implementing `ScraperPort` directly, with duplicated filtering, date parsing, and error handling logic. The provider-based architecture introduces an intermediate `RawJob` DTO, shared infrastructure components, and a clean separation of concerns:

```
Job Board (HTTP) → Provider (fetch + parse) → RawJob → Normalizer → Job
                                                         ↑
                                              Shared: DateParser,
                                              keyword matching,
                                              exclusion, location
                                              filtering, age check
```

## Architecture Diagram

```
                           ┌─────────────────────────────┐
                           │    ProviderRegistry          │
                           │  (orchestrates providers)    │
                           └──────────┬──────────────────┘
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                    ▼
          ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
          │   GupyProvider   │ │ InfoJobsProvider│ │  FutureProvider │
          │  (RestClient)    │ │  (Jsoup + HTTP)  │ │                 │
          └────────┬────────┘ └────────┬────────┘ └─────────────────┘
                   │                   │
                   ▼                   ▼
          ┌─────────────────┐ ┌─────────────────┐
          │ RestApiStrategy  │ │  HtmlStrategy   │
          │ (JSON → RawJob)  │ │ (HTML → RawJob) │
          └─────────────────┘ └─────────────────┘
                                      │
                                      ▼
                            ┌─────────────────────┐
                            │    JobNormalizer     │
                            │  (DateParser,        │
                            │   keyword matching,  │
                            │   exclusion,         │
                            │   location filter,   │
                            │   age check)         │
                            └──────────┬──────────┘
                                       ▼
                            ┌─────────────────────┐
                            │ ProviderBasedScraper │
                            │     Adapter          │
                            │  (implements         │
                            │   ScraperPort)       │
                            └─────────────────────┘
```

## Class Hierarchy

### Interfaces

| Interface | Package | Purpose |
|-----------|---------|---------|
| `ExtractionStrategy` | `infrastructure/scraper/provider` | Parses source → `List<RawJob>` |
| `RetryStrategy` | `infrastructure/scraper/retry` | Retry logic for provider calls |
| `RateLimiter` | `infrastructure/scraper/ratelimit` | Rate limiting per provider |

### Implementations

| Class | Implements | Purpose |
|-------|-----------|---------|
| `RestApiStrategy` | `ExtractionStrategy` | Parse JSON API responses into RawJob |
| `HtmlStrategy` | `ExtractionStrategy` | Parse HTML pages into RawJob via Jsoup |
| `ExponentialBackoffRetry` | `RetryStrategy` | Exponential backoff with jitter |
| `TokenBucketRateLimiter` | `RateLimiter` | Token bucket per provider |
| `GupyProvider` | `ExtractionStrategy` | Gupy job board fetcher |
| `InfoJobsProvider` | `ExtractionStrategy` | InfoJobs job board fetcher |
| `ProviderRegistry` | - | Orchestrates providers with retry + rate limit + normalization |
| `ProviderBasedScraperAdapter` | `ScraperPort` | Backward-compatible bridge |

### Supporting Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `RawJob` | `application/port/out` | Intermediate DTO between provider and normalizer |
| `DateParser` | `infrastructure/scraper/normalizer` | Parse date strings to `LocalDate` |
| `JobNormalizer` | `infrastructure/scraper/normalizer` | Full normalization pipeline: RawJob → Job |
| `JsonLdParser` | `infrastructure/scraper/parser` | Parse JSON-LD JobPosting from HTML |

## Data Flow

### Fetch Flow (happy path)

```
1. User calls FetchJobsService.fetch() via POST /api/jobs/fetch
2.   → ProviderBasedScraperAdapter.fetch()
3.     → ProviderRegistry fetches from each registered provider:
4.       → retry.execute(provider::extract)
5.         → rateLimiter.acquire(providerId)
6.           → Provider calls external API (Gupy/InfoJobs)
7.           → Provider uses strategy to parse response → List<RawJob>
8.       ← List<RawJob> returned
9.     → JobNormalizer.normalize(rawJob) for each
10.     → Dedup jobs by URL
11.   ← List<Job> returned
12. ← FetchJobsService returns List<Job>
```

### Error Handling

- **HTTP errors**: Provider catches, logs, continues to next keyword
- **Parsing errors**: Individual card failures don't break batch (try/catch per card in InfoJobsProvider)
- **Normalizer errors**: ProviderBasedScraperAdapter catches, logs, continues to next RawJob
- **All providers fail**: Returns empty list (graceful degradation vs old CompositeScraper which throws)

## Key Design Decisions

### 1. RawJob as intermediate DTO

**Decision**: Introduce `RawJob` in `application/port/out/` bridging provider (infrastructure) → normalizer (infrastructure).

**Rationale**: Providers parse raw source into an intermediate format. Normalizer converts to domain `Job`. This separates "how to extract data" from "how to filter and map data".

**Trade-off**: Extra class. Benefit: providers never touch domain model, normalizer never calls HTTP.

### 2. Shared Normalizer

**Decision**: `JobNormalizer` centralizes keyword matching, exclusion, location filtering, date parsing, and age checking.

**Rationale**: Old scrapers duplicated these. Now it's a single pipeline, easier to test and modify.

### 3. Strategy Pattern for Extraction

**Decision**: `ExtractionStrategy` interface with `RestApiStrategy` (JSON) and `HtmlStrategy` (HTML/Jsoup).

**Rationale**: A provider fetches data; a strategy parses it. Adding a new job board = new provider + select existing strategy.

### 4. Backward Compatibility via Adapter

**Decision**: New `ProviderBasedScraperAdapter` implements existing `ScraperPort`.

**Rationale**: Zero changes to `FetchJobsService`, controllers, or domain. Old scrapers preserved for side-by-side validation.

### 5. Retry and Rate Limiting as Decorators

**Decision**: `ExponentialBackoffRetry` and `TokenBucketRateLimiter` are applied by `ProviderRegistry`, not by providers.

**Rationale**: Providers are pure fetch+parse. Cross-cutting concerns (retry, rate limit) are configured centrally.

### 6. Graceful Degradation vs Exception

**Decision**: When ALL providers fail, new adapter returns empty list. Old `CompositeScraper` throws `ScraperException`.

**Rationale**: The manual trigger can be retried by the user. Downstream consumers (`FetchJobsService`) handle empty lists.

## Comparison: Old vs New

| Aspect | Old (CompositeScraper) | New (ProviderBasedScraperAdapter) |
|--------|----------------------|-----------------------------------|
| ScraperPort | GupyScraper, InfoJobsScraper implement directly | ProviderBasedScraperAdapter implements |
| Filtering | Per-scraper (duplicated) | Shared JobNormalizer |
| Date parsing | Per-scraper | Shared DateParser |
| Retry | InfoJobsScraper only, hardcoded | Configurable ExponentialBackoffRetry |
| Rate limiting | None | Configurable TokenBucketRateLimiter |
| Error isolation | One failing scraper fails all (Composite) | Per-provider isolation |
| All providers fail | Throws ScraperException | Returns empty list |

## Configuration

### application.yaml

```yaml
scraper:
  retry:
    max-attempts: 3
    base-delay-millis: 1000
    max-delay-millis: 30000
    max-jitter-millis: 1000
  rate-limiter:
    default-permits-per-second: 5
    default-burst: 3
  normalizer:
    max-age-days: 90
```

## Test Coverage

All new components follow TDD with JUnit 5 + Mockito + WireMock:

| Component | Test File | Scenarios |
|-----------|-----------|-----------|
| RawJob | RawJobTest | Construction, null rejection |
| DateParser | DateParserTest | Relative/absolute/unknown dates |
| ExponentialBackoffRetry | ExponentialBackoffRetryTest | Success, exhaust, non-retryable |
| TokenBucketRateLimiter | TokenBucketRateLimiterTest | Tokens, refill, concurrent |
| JsonLdParser | JsonLdParserTest | Valid/missing/malformed JSON-LD |
| JobNormalizer | JobNormalizerTest | All pipeline stages |
| RestApiStrategy | RestApiStrategyTest | Mapping, missing fields |
| HtmlStrategy | HtmlStrategyTest | Cards, isolated failure, fallback |
| GupyProvider | GupyProviderTest | Single keyword, dedup, HTTP error |
| InfoJobsProvider | InfoJobsProviderTest | Single keyword, bot, isolated failure |
| ProviderRegistry | ProviderRegistryTest | All succeed, partial fail, all fail, dedup |
| ProviderBasedScraperAdapter | ProviderBasedScraperAdapterTest | Adapter delegation scenarios |
| ScrapingComparisonTest | ScrapingComparisonTest (temp) | Side-by-side validation |

## Migration Status

- [x] All 16 implementation tasks complete
- [x] 187 tests pass (0 failures, 1 pre-existing Docker-dependent error)
- [x] Side-by-side comparison: old vs new produce identical results
- [x] Backward compatible: FetchJobsService unchanged, ScraperPort unchanged
- [x] Old scrapers preserved (GupyScraper, InfoJobsScraper, CompositeScraper)
- [ ] `@Primary` still on CompositeScraper — can be switched to ProviderBasedScraperAdapter after validation
- [ ] Old scrapers can be deleted in a follow-up PR
