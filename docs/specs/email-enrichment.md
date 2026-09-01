# Spec: Email Enrichment — Scraping Improvements for Self-Hosted (P0+P1+P2)

> **Layers:** `infrastructure` (scraper, normalizer, persistence) + `web` + `application`
> **Primary files:** `JobNormalizer`, `InfoJobsProvider`, `GupyProvider`, `LinkedInProvider`, `CompanySiteEnricher` (new), `RawJob`, `Job`, `JobEntity`, `FetchJobsService`, `JobController`
> **Tests:** `JobNormalizerTest`, `InfoJobsProviderTest`, `CompanySiteEnricherTest` (new), `GupyProviderTest`, `ProviderBasedScraperAdapterTest`
> **Migration:** `V2__add_company_website_and_email_indexes.sql`
> **Replaces/Extends:** `contact-email-extraction.md` (scenarios 1-8 remain, new scenarios 9-18 added)

---

## Context

The project is **self-hosted**. The current `hasEmail` rate is low because:

- `JobNormalizer.extractContactEmail()` runs **before** `decodeEntities()`, on raw `html()` and misses `&#64;`, `[at]`, `[arroba]` and `mailto:`.
- `InfoJobsProvider` only parses the **card snippet** (~2000 chars), never fetching the job detail page where the email lives.
- `GupyProvider` discards `careerPageUrl` (company site) instead of persisting it.
- No provider crawls the **company website** (`/` + `/contato`). LinkedIn in particular almost never contains an email in the job description itself.
- `POST /api/jobs/fetch` returns `{"message":"success"}` with no per-provider observability, so `hasEmail` regressions are invisible.

This spec was **grilled** before writing. Locked decisions from grilling:

| Decision | Choice |
|----------|--------|
| Scope | **P0+P1+P2** (fix extractor + InfoJobs detail + companyWebsite + company site crawl). P3 AI fallback is out of scope. |
| InfoJobs detail | **Concurrent + fallback snippet** — virtual threads + rate limit; if detail fetch fails after retry, keep snippet and mark `metadata.detailFailed=true`. |
| Email priority | **`mailto:` > text regex > de-obfuscated** — first valid wins; filters `noreply/donotreply/no-reply/apply` + `example.com/exemplo.com/test.com/domain.com`. Obfuscations in v1: `[at]`, `(at)`, `[dot]`, `(dot)`, `[arroba]`, `(arroba)`, `&#64;`/`&#x40;`, zero-width chars. |
| Company website | **Metadata + column + in-memory cache** — `Gupy careerPageUrl` + HTML parse for InfoJobs/LinkedIn → `RawJob.metadata.companyWebsite` → `jobs.company_website` (nullable) + `ConcurrentHashMap<String, CompanyContact>` TTL 24h in memory (no `company_contacts` table in v1). |
| Crawl policy | **Conservative** — virtual threads, `Semaphore(2)` concurrent fetches, `1 req/s per domain` via `TokenBucketRateLimiter`, `timeout 5s`, respect `robots.txt` (`/robots.txt` check before crawl), max **2 pages** per company (`/` and `/contato` or `/contact` or `/trabalhe-conosco` or `/carreiras` — first that exists). |
| Observability | **New contract + indexes** — `POST /api/jobs/fetch` returns `FetchResult`; add `idx_jobs_contact_email` + `idx_jobs_company_website`. |
| Enrichment order | **Job desc → company crawl** — only crawl company site if `contactEmail == null && companyWebsite != null`. If job desc already has a valid email, skip crawl even if site has another. |
| Validation | **Only current filters** — no MX lookup, no gmail/outlook preference in v1. |
| Acceptance | **WireMock unit tests + hasEmail rate** — per-component tests + `FetchResult perProvider` integration; acceptance requires `hasEmail` rate +≥5pp on a local real fetch sample. |

---

## Goals

- Increase `hasEmail` rate by ≥5pp on a manual local fetch (measured via new `FetchResult.withEmail` / `perProvider.withEmail`).
- Fix silent email loss (decode, obfuscation, mailto).
- Fetch InfoJobs full description (detail page) without losing jobs on transient failure.
- Capture `companyWebsite` for future enrichment and expose it for debugging.
- Add self-hosted, zero-cost company-site crawl with safe rate limiting.

## Non-Goals

- AI extraction (Ollama/Hermes prompt) — P3, separate spec.
- MX/DNS validation, SMTP verification, Hunter-style inference — out of scope.
- Proxy rotation, CAPTCHA bypass, login — forbidden (same policy as `infojobs-scraper.md`).
- Persistent `company_contacts` table — deferred (in-memory cache only in v1).
- Retroactive backfill of old jobs — out of scope (only new fetches enriched).

---

## Expected behavior

### P0 — Fix extractor (JobNormalizer)

#### Scenario 1: mailto: has priority
- **GIVEN** a `RawJob` with description `'<a href="mailto:rh@techcorp.com">Apply</a> or contact jobs@techcorp.com'`
- **WHEN** `normalize(raw)` is called
- **THEN** `contactEmail` is `"rh@techcorp.com"` (mailto extracted first)

#### Scenario 2: decode before extract
- **GIVEN** a `RawJob` with description `'Send to hiring&#64;techcorp.com or hiring&#x40;techcorp.com'`
- **WHEN** `normalize(raw)` is called
- **THEN** `contactEmail` is `"hiring@techcorp.com"` (HTML entities decoded before regex)

#### Scenario 3: obfuscated [at]/[dot]/[arroba]
- **GIVEN** a `RawJob` with description `'Contact rh [at] techcorp [dot] com or rh [arroba] techcorp.com or rh(at)techcorp(dot)com'`
- **WHEN** `normalize(raw)` is called
- **THEN** `contactEmail` is `"rh@techcorp.com"` (de-obfuscation normalized before regex, first valid wins)

#### Scenario 4: zero-width chars
- **GIVEN** a `RawJob` with description containing `'rh\u200B@techcorp.com'` (zero-width space)
- **WHEN** `normalize(raw)` is called
- **THEN** zero-width chars (`\u200B`, `\u200C`, `\u200D`, `\uFEFF`) are stripped before regex

#### Scenario 5: extraction runs on text, not html
- **GIVEN** a LinkedIn detail `RawJob` with description `'<div class="user@domain">Contact apply@company.com visible</div>'` (CSS false positive) + mailto `rh@company.com` in anchor
- **WHEN** `normalize(raw)` is called
- **THEN** regex runs on `Jsoup.parse(html).text()` after `decodeEntities()` plus separate `a[href^=mailto:]` scan; `apply@` is filtered; result is `rh@company.com`

#### Scenario 6: existing filters still apply
- **GIVEN** a `RawJob` with description `'noreply@company.com, donotreply@company.com, no-reply@company.com, apply@company.com, test@example.com'`
- **WHEN** `normalize(raw)` is called
- **THEN** `contactEmail` is `null` (all filtered)

#### Scenario 7: title searched before description, but mailto priority overrides order
- **GIVEN** a `RawJob` with title `'Developer — jobs@company.com'` and description `'<a href="mailto:rh@company.com">RH</a>'`
- **WHEN** `normalize(raw)` is called
- **THEN** `contactEmail` is `"rh@company.com"` (mailto wins even though title has a text email)

### P1 — InfoJobs detail fetch + companyWebsite

#### Scenario 8: InfoJobs fetches detail page concurrently
- **GIVEN** InfoJobs search returns 10 cards with URLs
- **WHEN** `InfoJobsProvider.extract()` is called
- **THEN** it fetches each `jobUrl` detail page **concurrently** (virtual threads, `Semaphore(2)`, `1 req/s per domain`, `retry 3x` with `ExponentialBackoffRetry`, `timeout 5s`)
- **AND** full description is parsed from detail HTML (`[data-testid=job-description]` or `.description` or JSON-LD `description`)
- **AND** `RawJob.description` is the full detail text (not the card snippet)

#### Scenario 9: InfoJobs detail fetch fallback
- **GIVEN** a card with URL `https://www.infojobs.com.br/vaga/123` where detail fetch fails with timeout/5xx/bot-challenge after retries
- **WHEN** `InfoJobsProvider.extract()` is called
- **THEN** that job is **not discarded** — `RawJob` is built from the card snippet
- **AND** `RawJob.metadata.detailFailed=true` is set for observability
- **AND** other cards continue processing (isolated failure)

#### Scenario 10: Gupy persists companyWebsite
- **GIVEN** a Gupy JSON node has `careerPageUrl: "https://techcorp.gupy.io"` and `jobUrl: "https://techcorp.gupy.io/jobs/123"`
- **WHEN** `GupyProvider` maps it
- **THEN** `RawJob.metadata.companyWebsite="https://techcorp.gupy.io"` is set (not just fallback for `url`)
- **AND** `JobNormalizer` copies it to `Job.companyWebsite`

#### Scenario 11: InfoJobs/LinkedIn parse companyWebsite from HTML
- **GIVEN** an InfoJobs card or LinkedIn detail page contains `<a href="https://techcorp.com.br">Empresa</a>` or `topcard__org-name-link`
- **WHEN** the provider maps it
- **THEN** `RawJob.metadata.companyWebsite` is set to that absolute URL (normalized, no trailing slash)
- **AND** if no company link exists, `companyWebsite` stays `null`

### P2 — Company site crawl (CompanySiteEnricher)

#### Scenario 12: crawl only when job has no email
- **GIVEN** a `Job` with `contactEmail="rh@company.com"` and `companyWebsite="https://company.com"`
- **WHEN** enrichment runs
- **THEN** `CompanySiteEnricher` is **not called** (skip)

#### Scenario 13: crawl homepage + /contato when email is null
- **GIVEN** a `Job` with `contactEmail=null` and `companyWebsite="https://techcorp.com.br"`
- **WHEN** `CompanySiteEnricher.enrich(job)` is called
- **THEN** it fetches `https://techcorp.com.br` (respecting `robots.txt`) and if no email, tries `https://techcorp.com.br/contato` (fallbacks: `/contact`, `/trabalhe-conosco`, `/carreiras` — first that returns 200)
- **AND** extracts email with same priority `mailto: > text > de-obfuscated` and same filters
- **AND** returns enriched `Job` with `contactEmail` set if found

#### Scenario 14: crawl respects rate limit and robots.txt
- **GIVEN** `CompanySiteEnricher` is called for 20 jobs from 5 distinct domains
- **WHEN** enrichment runs
- **THEN** at most `2` concurrent HTTP fetches run (Semaphore)
- **AND** per-domain rate is `≤1 req/s` via `TokenBucketRateLimiter`
- **AND** if `https://domain/robots.txt` disallows `/` or `/contato`, that page is skipped and logged

#### Scenario 15: crawl is cached in memory
- **GIVEN** two jobs share `companyWebsite` domain `techcorp.com.br` where homepage contains `contato@techcorp.com.br`
- **WHEN** first job is enriched (fetch + parse) and then second job is enriched within 24h
- **THEN** second enrichment hits the in-memory `ConcurrentHashMap<String, CachedContact>` and makes **no HTTP request**

#### Scenario 16: crawl failure is non-fatal
- **GIVEN** `companyWebsite` fetch times out or returns 403/5xx after retries
- **WHEN** `CompanySiteEnricher.enrich(job)` is called
- **THEN** it returns `job` unchanged (`contactEmail` stays `null`), logs at `WARN`, never throws

### P1+P2 — Observability

#### Scenario 17: fetch returns per-provider stats
- **GIVEN** a user calls `POST /api/jobs/fetch`
- **WHEN** all providers complete (some may have enriched via company crawl)
- **THEN** response is `FetchResult` containing `totalFetched`, `totalSaved`, `totalWithEmail`, and `perProvider: [{source, fetched, saved, withEmail, error, detailFailedCount}]`
- **AND** `totalWithEmail` counts jobs where `contactEmail != null` after enrichment

#### Scenario 18: hasEmail filter benefits from index
- **GIVEN** `GET /api/jobs?hasEmail=true` and `GET /api/jobs?hasEmail=false`
- **WHEN** the persistence adapter queries `WHERE contact_email IS [NOT] NULL`
- **THEN** queries use `idx_jobs_contact_email` (no full table scan on large `jobs`)

---

## Business rules

1. **Decode before extract** — `JobNormalizer.normalize()` must call `decodeEntities()` + `Jsoup.parse(html).text()` **before** `extractContactEmail()`. The `mailto:` scan runs on the parsed DOM (`select a[href^=mailto:]`) before text regex.
2. **De-obfuscation normalization** (applied to text copy before regex): case-insensitive replace `\s*\[at\]\s*` / `\(at\)` / `\s+AT\s+` → `@`; `\s*\[dot\]\s*` / `\(dot\)` → `.`; `\s*\[arroba\]\s*` / `\(arroba\)` → `@`; decode `&#64;`/`&#x40;`/`&amp;`; strip `[\u200B\u200C\u200D\uFEFF]`. Original description stored in `Job` is still the cleaned decoded text, not the de-obfuscated copy.
3. **Priority** — `a[href^=mailto:]` (DOM order) → text regex on de-obfuscated copy → first `EMAIL_PATTERN` match that passes `isContactEmail()`. First valid wins.
4. **Filters unchanged** — `noreply@`, `donotreply@`, `no-reply@`, `apply@` (prefix, case-insensitive) and placeholder domains `example.com`, `exemplo.com`, `test.com`, `domain.com`, `yourdomain.com`, `seuemail.com` are rejected.
5. **InfoJobs detail fetch** — must be concurrent (virtual threads), `Semaphore(2)`, `1 req/s per domain`, `ExponentialBackoffRetry` (3 attempts, base 1s, max 30s, jitter 2s), `timeout 5s`. Per-card failure is isolated; fallback to snippet with `metadata.detailFailed=true`.
6. **companyWebsite** — `RawJob.metadata.companyWebsite` is an absolute URL string (nullable). `Job` gains `companyWebsite` field (nullable `String`). `JobEntity` gains `company_website VARCHAR(512)`. `GupyProvider` uses `careerPageUrl` as companyWebsite even when `jobUrl` is present. HTML providers parse the company link from the card/detail DOM.
7. **CompanySiteEnricher trigger** — only when `job.contactEmail == null && job.companyWebsite != null`. Enricher is called from `ProviderBasedScraperAdapter` **after** `JobNormalizer` but **before** dedup/save, and from `FetchSourceJobsService` same point. If job already has email, skip.
8. **Enricher crawl** — `GET companyWebsite` → if no email, try one of `["/contato","/contact","/trabalhe-conosco","/carreiras"]` (first 200). Each fetch uses same retry/rateLimiter/timeout policy as providers. Respect `robots.txt` (fetch and parse before crawl; if disallowed, skip page). Max 2 HTTP fetches per job. Cache key is **domain** (host), value `CachedContact{email, fetchedAt}`, TTL 24h, `ConcurrentHashMap`, not persisted.
9. **Threading** — enrichment of the batch is concurrent with same `Semaphore(2)`; `JobNormalizer` remains single-threaded per RawJob.
10. **Observability** — `FetchJobsService.fetchAndSave()` returns `FetchResult` (not just saved list) and `JobController POST /api/jobs/fetch` serializes it. `FetchSourceJobsService` same. `perProvider.error` is `null` on success, otherwise exception message truncated to 300 chars.
11. **Indexes** — `idx_jobs_contact_email ON jobs(contact_email)` and `idx_jobs_company_website ON jobs(company_website)`.

---

## Data model

### Migration `V2__add_company_website_and_email_indexes.sql`

```sql
ALTER TABLE jobs ADD COLUMN company_website VARCHAR(512);

CREATE INDEX idx_jobs_contact_email ON jobs(contact_email);
CREATE INDEX idx_jobs_company_website ON jobs(company_website);
```

### Domain changes

```java
// domain/model/Job.java — add field
public record Job(
    Long id,
    String title,
    String company,
    String url,
    String description,
    LocalDate postedAt,
    String source,
    String contactEmail,      // existing
    String companyWebsite     // NEW, nullable, absolute URL
) { ... }

// application/port/out/RawJob.java — metadata key
// metadata.put("companyWebsite", "https://...")  // NEW
// metadata.put("detailFailed", "true")           // NEW, InfoJobs fallback marker

// JobEntity.java — add column
@Column(name = "company_website", length = 512)
private String companyWebsite;
```

### New class

```java
// infrastructure/scraper/enricher/CompanySiteEnricher.java
public class CompanySiteEnricher {
    // In-memory cache: domain -> CachedContact
    // CachedContact { String email; Instant fetchedAt; }
    public Job enrich(Job job); // returns same or enriched copy; never throws
    private Optional<String> extractFromHtml(String html); // mailto > text > deobfuscated
    private boolean isAllowedByRobots(String domain, String path);
}
```

---

## Architecture

```
POST /api/jobs/fetch → FetchJobsService → ProviderBasedScraperAdapter
                                         ├─ RateLimiter.acquire(providerId)  // NOW WIRED
                                         ├─ Retry.execute(provider::extract) // wired for all providers
                                         ├─ GupyProvider / InfoJobsProvider (detail concurrent) / LinkedInProvider|Client
                                         │     → List<RawJob> (with metadata.companyWebsite, detailFailed)
                                         ├─ JobNormalizer.normalize() // decode → mailto → deobfuscate → regex → filters
                                         ├─ CompanySiteEnricher.enrich(job) // if null email && website != null → crawl (cached, rate-limited, robots)
                                         ├─ dedup by URL
                                         └─ save → FetchResult{totalSaved, withEmail, perProvider[]}
```

`ProviderRegistry` remains orchestrator but `ProviderBasedScraperAdapter` now owns the `RateLimiter`/`Retry` wiring so both `FetchJobsService` and `FetchSourceJobsService` benefit. `AppConfig` wires a shared `RestClient` bean (connection pooling) and the new `CompanySiteEnricher` singleton.

---

## Interface contracts

```java
// New FetchResult DTO (application/port/in or web/dto)
public record FetchResult(
    int totalFetched,
    int totalSaved,
    int totalWithEmail,
    List<ProviderFetchStats> perProvider
) {}

public record ProviderFetchStats(
    String source,        // "gupy", "infojobs", "linkedin"
    int fetched,          // RawJobs before normalizer
    int saved,            // Jobs persisted
    int withEmail,        // subset of saved where contactEmail != null
    int detailFailedCount,// InfoJobs detail fallback count
    String error          // null on success, else truncated message
) {}

// CompanySiteEnricher port (infrastructure only)
public interface CompanySiteEnrichmentPort {
    Job enrich(Job job);
}
```

`ScraperPort` stays `List<RawJob> fetch()` — enrichment happens in the adapter/service layer, not inside providers.

---

## Configuration (application.yaml additions)

```yaml
scraper:
  rest-client:
    timeout-seconds: 10
    max-connections: 20
  infojobs:
    detail-concurrency: 2
    detail-timeout-seconds: 5
  enricher:
    enabled: true
    timeout-seconds: 5
    max-pages-per-company: 2
    concurrency: 2
    per-domain-permits-per-second: 1
    cache-ttl-hours: 24
    contact-paths: /contato,/contact,/trabalhe-conosco,/carreiras
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| InfoJobs detail 403/429/bot challenge after retries | none (isolated) | Use card snippet, set `detailFailed=true`, continue batch |
| InfoJobs detail timeout/5xx after retries | none (isolated) | Same as above |
| Company site 403/429/timeout/bot after retries | none | Return job unchanged, log WARN, continue batch |
| robots.txt disallows page | none | Skip that page, try next contact path if applicable |
| Company site returns no email | none | Return job unchanged |
| All providers fail | none | `FetchResult` with `totalSaved=0`, each `perProvider.error` populated |
| Invalid `companyWebsite` URL | none | Skip enrichment for that job, log DEBUG |

---

## Test plan (TDD — RED → GREEN → REFACTOR)

### Unit: JobNormalizerTest (extend)

- `normalize_whenMailtoPresent_shouldPreferMailtoOverTextEmail()`
- `normalize_whenHtmlEntities_shouldDecodeBeforeExtract()`
- `normalize_whenObfuscatedAtDotArroba_shouldDeobfuscateAndExtract()`
- `normalize_whenZeroWidthChars_shouldStripAndExtract()`
- `normalize_whenHtmlWithCssFalsePositive_shouldIgnoreClassAndExtractMailto()`
- `normalize_whenApplyAndNoreply_shouldFilterAndReturnNull()`

### Unit: InfoJobsProviderTest (WireMock)

- `extract_whenDetailAvailable_shouldUseFullDescription()` — search mock + 3 detail mocks, assert full text used
- `extract_whenDetailFails_shouldFallbackToSnippetAndMarkDetailFailed()` — detail mock 500, assert snippet kept
- `extract_whenDetailConcurrent_shouldRespectConcurrency()` — verify at most 2 concurrent detail calls via WireMock delay
- `extract_whenCompanyLinkPresent_shouldSetCompanyWebsiteMetadata()`

### Unit: GupyProviderTest (WireMock)

- `extract_whenCareerPageUrlPresent_shouldSetCompanyWebsite()` — assert metadata

### Unit: CompanySiteEnricherTest (WireMock)

- `enrich_whenJobHasEmail_shouldSkipCrawlAndMakeNoRequest()`
- `enrich_whenNoEmail_shouldCrawlHomepageAndExtractMailto()`
- `enrich_whenHomepageNoEmail_shouldTryContactPath()`
- `enrich_whenRobotsDisallows_shouldSkipPage()`
- `enrich_whenSameDomainTwice_shouldHitCacheOnSecondCall()` — verify 1 HTTP call
- `enrich_whenCrawlFails_shouldReturnUnchangedAndNotThrow()`
- `enrich_whenRateLimited_shouldThrottlePerDomain()` — verify delay

### Integration: ProviderBasedScraperAdapterTest + FetchJobsServiceTest

- `fetch_whenMixedProviders_shouldReturnFetchResultWithPerProviderStats()`
- `fetchAndSave_whenEnrichedEmailFound_shouldPersistWithEmail()`

### Manual acceptance (self-hosted)

- Run `POST /api/jobs/fetch` locally, capture `FetchResult`. Assert `totalWithEmail / totalSaved` is ≥5pp higher than baseline (same keywords, same `max-age-days`). Log is the evidence artifact.

---

## Out of scope

- AI extraction via Ollama/Hermes (P3) — separate spec.
- MX/DNS or SMTP deliverability check.
- `company_contacts` persistent table — in-memory cache only in v1.
- Backfill of existing `jobs` rows (only new fetches enriched).
- Proxy, CAPTCHA, login, or any bypass of bot protections.
- LinkedIn Playwright microservice changes (`linkedin-scraper/`) — out of scope in v1 (Java-side only).

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/email-enrichment.md.

Phase 1 — RED (tests first, no implementation):
- Extend JobNormalizerTest with 6 new cases (Scenarios 1-7) — must fail.
- Extend InfoJobsProviderTest with 4 new cases (Scenarios 8-9,11) — WireMock, must fail.
- Extend GupyProviderTest with 1 case (Scenario 10) — must fail.
- Create CompanySiteEnricherTest with 7 cases (Scenarios 12-16) — must fail.
- Add V2 migration file (not yet applied).

Phase 2 — GREEN (minimal implementation, after confirmation):
- Fix JobNormalizer: decode→parse→mailto→deobfuscate→regex (Scenarios 1-7).
- Make InfoJobsProvider fetch detail concurrently with Semaphore(2) + retry + fallback (Scenarios 8-9).
- Persist companyWebsite in GupyProvider/InfoJobsProvider/LinkedInProvider → RawJob.metadata → Job → JobEntity (Scenarios 10-11).
- Implement CompanySiteEnricher (Scenarios 12-16) with cache 24h, robots.txt, rate limit.
- Wire RateLimiter+Retry in ProviderBasedScraperAdapter and shared RestClient bean.
- Change FetchJobsService + JobController to return FetchResult (Scenario 17) + add indexes (Scenario 18).

Phase 3 — REFACTOR: extract deobfuscation helper, robots parser, cache class without changing behavior.
```

