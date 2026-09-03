# Spec: Async Company Enrichment — Remove Enricher from Fetch Hot Path

> **Layers:** `application` (service + ports) + `infrastructure` (adapter, persistence, enricher) + `web`
> **Primary files:** `ProviderBasedScraperAdapter`, `FetchJobsService`, `FetchSourceJobsService`, `CompanyEnrichmentService` (new), `JobRepository`, `JobJpaRepository`, `JobPersistenceAdapter`, `JobController`
> **Tests:** `CompanyEnrichmentServiceTest` (new), `ProviderBasedScraperAdapterTest`, `FetchJobsServiceTest`
> **Extends:** `email-enrichment.md` P2 (CompanySiteEnricher behavior unchanged — only its trigger moves)
> **Motivation:** Hermes agent on VM reported `POST /api/jobs/fetch` blocked ~11+ min on 512 Gupy jobs (each `*.gupy.io` crawl = 2 pages × 3 retries × backoff ≈ 5s; 512×5s/2 concurrency ≈ 20-25 min synchronous). Hotfix `834c783` skips portal domains, but any real corporate batch still blocks fetch. Enrichment must move out-of-band.

---

## Goals

- `POST /api/jobs/fetch` returns in seconds (no company-site HTTP in hot path).
- Company-site crawl still happens, but as an explicit out-of-band batch job keyed by distinct domain.
- Persist enriched emails back to existing rows (update, not duplicate).
- No commit lost: `CompanySiteEnricher` extraction logic (mailto > text > de-obfuscated, robots, cache 24h, 1 req/s, Semaphore(2), portal-domain skip) is reused unchanged.

## Non-Goals

- No `@Async`/thread-pool auto-trigger after fetch in v1 (explicit endpoint only — predictable on self-hosted VM).
- No `company_contacts` persistent table (keep in-memory cache in enricher).
- No MX/SMTP validation.
- No backfill of jobs with `companyWebsite IS NULL`.

---

## Expected behavior

### Scenario 1: fetch does not crawl company sites
- **GIVEN** providers return jobs with `companyWebsite` set (e.g. real corporate domains)
- **WHEN** `POST /api/jobs/fetch` is called
- **THEN** response returns without any company-site HTTP (fetch only does provider extract → normalize → dedup → save)
- **AND** `FetchResult` is unchanged in shape (`totalFetched/totalSaved/totalWithEmail/perProvider`)

### Scenario 2: batch enrichment endpoint enriches by distinct domain
- **GIVEN** DB has 10 jobs with `contactEmail IS NULL`, sharing 3 distinct corporate `companyWebsite` domains
- **WHEN** `POST /api/jobs/enrich-emails?limit=50` is called
- **THEN** the service crawls at most 3 domains (one crawl per domain, cache reused across jobs)
- **AND** every job whose domain yielded an email is updated in place (`contactEmail` set, same `id`, no duplicate row)
- **AND** response is `EnrichmentResult{checked, enriched, skippedPortal, failed}`

### Scenario 3: portal domains skipped without HTTP
- **GIVEN** jobs with `companyWebsite` on `*.gupy.io` / `*.infojobs.com.br`
- **WHEN** enrichment runs
- **THEN** they are counted as `skippedPortal`, no HTTP is made (existing `isPortalDomain` guard)

### Scenario 4: enrichment failure is non-fatal per domain
- **GIVEN** one domain times out / 403 / robots-disallowed
- **WHEN** batch enrichment runs
- **THEN** jobs of that domain keep `contactEmail=null`, counted as `failed`, other domains continue

### Scenario 5: limit bounds the batch
- **GIVEN** 500 jobs needing enrichment
- **WHEN** `POST /api/jobs/enrich-emails?limit=20` is called
- **THEN** at most 20 jobs are checked (oldest first by `id`), response reflects the bounded run

---

## Business rules

1. **Adapter stops calling enricher** — `ProviderBasedScraperAdapter.fetch()` no longer invokes `CompanySiteEnricher` (constructor overload kept for compat, enricher arg ignored or removed in v1 — prefer removal + AppConfig update). Same for `FetchSourceJobsService` (drop enricher call).
2. **New application service** `CompanyEnrichmentService.enrichMissingEmails(int limit)`:
   - loads candidates via `JobRepository.findJobsNeedingEnrichment(limit)` (`contactEmail IS NULL AND companyWebsite IS NOT NULL`, ordered by `id ASC`, bounded by `limit`, default 50, max 200).
   - groups by domain (host of `companyWebsite`, lowercase); portal domains counted as `skippedPortal` without crawl.
   - for each remaining domain (in first-seen order): enrich ONE representative job via `CompanySiteEnrichmentPort.enrich()`, then apply the resulting email to ALL jobs of that domain in the batch (single crawl per domain).
   - persists updates via `JobRepository.save()` (entity carries `id`, JPA merge updates in place — no duplicate rows).
   - never throws; per-domain try/catch → `failed` count.
3. **Repository addition** — `JobRepository.findJobsNeedingEnrichment(int limit)` + `JobJpaRepository` query + adapter mapping. No schema change.
4. **Controller** — `POST /api/jobs/enrich-emails?limit=` returns `EnrichmentResult{checked, enriched, skippedPortal, failed}` (200). Auth required like other job endpoints.
5. **FetchResult semantics unchanged** — `totalWithEmail` in fetch now counts only normalizer/description emails (no company-site contribution). Company-site gains appear after the enrich endpoint runs (visible via `GET /api/jobs?hasEmail=true`).

---

## Interface contracts

```java
// application/port/in
public record EnrichmentResult(int checked, int enriched, int skippedPortal, int failed) {}

public interface CompanyEnrichmentUseCase {
    EnrichmentResult enrichMissingEmails(int limit);
}

// application/port/out (addition)
public interface JobRepository {
    // existing...
    List<Job> findJobsNeedingEnrichment(int limit);
}

// application/service (new)
public class CompanyEnrichmentService implements CompanyEnrichmentUseCase {
    public EnrichmentResult enrichMissingEmails(int limit);
}

// web/dto (new)
public record EnrichmentResultResponse(int checked, int enriched, int skippedPortal, int failed) {}
```

---

## Configuration

```yaml
enricher:
  batch-default-limit: 50
  batch-max-limit: 200
```

Reuse existing `scraper.enricher.*` (timeout, concurrency, cache-ttl, contact-paths) for crawl policy — no change.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| `limit <= 0` or `> max` | none | Clamp to `[1, max]` |
| No candidates | none | `{0,0,0,0}` |
| Domain crawl fails / robots-disallow | none | That domain's jobs unchanged, `failed++` |
| Save fails for one job | none | Count as `failed`, continue batch |

---

## Test plan (TDD — RED → GREEN → REFACTOR)

### Unit: CompanyEnrichmentServiceTest (new, Mockito)
- `enrich_whenSharedDomain_shouldCrawlOnceAndUpdateAll()` — 3 jobs same domain, verify enricher called once, all saved with email
- `enrich_whenPortalDomain_shouldSkipWithoutCrawl()` — verify 0 enricher interactions, `skippedPortal=1`
- `enrich_whenDomainFails_shouldCountFailedAndContinue()` — enricher returns unchanged for domain A, email for domain B
- `enrich_whenLimitBounds_shouldCheckAtMostLimit()` — 5 candidates, limit=2 → checked=2
- `enrich_whenNoCandidates_shouldReturnZeros()`

### Unit: ProviderBasedScraperAdapterTest (extend)
- `fetch_whenJobsHaveCompanyWebsite_shouldNotCallEnricher()` — enricher mock verify 0 interactions

### Unit: FetchJobsServiceTest (extend)
- `fetchAndSave_shouldNotEnrichAndReturnFast()` — assert FetchResult shape unchanged, no enricher involvement

### Manual acceptance (VM, self-hosted)
- `POST /api/jobs/fetch` returns in <30s (no company-site crawl in logs)
- `POST /api/jobs/enrich-emails?limit=50` then `GET /api/jobs?hasEmail=true` count increases

---

## Out of scope

- Auto-trigger (`@Async`/`@Scheduled`) after fetch — explicit endpoint only in v1.
- Persistent `company_contacts` table.
- Endpoint per single job (`POST /api/jobs/{id}/enrich`) — batch only in v1.
- LinkedIn Playwright microservice changes.

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/async-company-enrichment.md.

Phase 1 — RED (tests first):
- Create CompanyEnrichmentServiceTest with 5 cases above (Mockito, must fail).
- Extend ProviderBasedScraperAdapterTest with no-enricher-call case (must fail until adapter updated).

Phase 2 — GREEN (after confirmation):
- Remove enricher call from ProviderBasedScraperAdapter + FetchSourceJobsService; update AppConfig wiring.
- Add JobRepository.findJobsNeedingEnrichment + JPA query + adapter mapping.
- Implement CompanyEnrichmentService with domain-deduped batch + EnrichmentResult.
- Add POST /api/jobs/enrich-emails controller + DTO + config props.

Phase 3 — REFACTOR: extract domain-grouping helper without behavior change.
```
