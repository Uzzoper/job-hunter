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
   - log one INFO outcome per domain: `email-found`, `no-email`, `robots-disallowed`, or `no-email source=cache`. A domain where robots disallows every eligible page or every eligible page fails to load is counted as failed; a successful crawl with no email is not.
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
| All eligible pages fail / robots-disallow | none at endpoint | That domain's jobs unchanged, `failed++`, other domains continue |
| Crawl succeeds without an email | none | That domain remains unchanged and is logged as `no-email`; not counted as failed |
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

## Phase 2 — Batch time budget (issue #117)

### Context (measured, 2026-11-17 census)

- Eligible population (local DB): 34 jobs / 22 distinct domains — 1.5% of jobs carry `company_website` (detail-fetch coverage artifact; population expansion is a separate spec).
- The real enricher over a cold cache yielded **5/22 domains (23%)**; **6/22 (27%)** blocked by robots `Disallow: /`; 4/22 unreachable (TLS/301/bot interstitial).
- The batch loop is **sequential**; the shared scraper retry (3 attempts × 10s timeout + backoff) makes one dead/slow host cost up to ~33s. Observed slow crawls: 13–21s per domain. A batch of 50 cannot complete inside a 120s window — even the tiny eligible set gets partial coverage.
- Cold outreach is a product goal (owner decision 2026-11-17): every scheduled domain must get a fair attempt regardless of individual host health.

### Goals

- No single host can starve the batch (per-domain wall budget).
- Domains are processed concurrently **across domains**; per-host pacing (≤1 req/s, spec Scenario 14) is unchanged.
- Global batch budget retained; partial runs reported honestly.
- No changes to robots policy, per-host rate limiting, or hit-rate behavior.

### Business rules

1. Each domain crawl runs as a bounded task with budget
   `scraper.enricher.batch-domain-budget-seconds` (default **15**). Timeout → that
   domain's jobs count as `failed`, batch continues.
2. Domain-level concurrency: `scraper.enricher.batch-domain-concurrency` (default
   **4**) tasks in flight. Cross-domain parallelism never raises per-host rate.
3. Global wall budget: `scraper.enricher.batch-budget-seconds` (default **120**).
   When reached, no new domain is scheduled; in-flight tasks finish or hit their
   own budget.
4. Domains never attempted because of the global budget are reported via a new
   `EnrichmentResult` field `domainsSkippedBudget` (additive JSON field; record
   shape otherwise unchanged — response consumers keep working).
5. Failure semantics otherwise unchanged: a domain crawled within budget that
   yields no email is **not** failed.
6. Domain visit order: first-seen order preserved (LinkedHashMap grouping unchanged).

### Scenarios

| Scenario | Input | Expected |
|---|---|---|
| Slow domain exceeds budget | domain A sleeps > budget, domain B fast email | B enriched, A failed, batch returns well under A's sleep |
| Dead host | connection failures until budget | `failed = count(jobs of A)`, batch continues |
| Concurrency | 4 slow domains, concurrency 4 | wall ≈ slowest domain, not the sum |
| Global budget hit | many slow domains, tiny global budget | no exception; enriched-so-far returned; `domainsSkippedBudget > 0` |
| Fair attempt | batch completes normally | every scheduled domain attempted exactly once |

### Configuration (all reversible, yaml)

```yaml
scraper.enricher:
  batch-domain-concurrency: 4      # domains crawled in parallel
  batch-domain-budget-seconds: 15  # per-domain wall budget
  batch-budget-seconds: 120        # global batch wall budget
```

### Test plan (TDD — RED → GREEN)

Unit: `CompanyEnrichmentServiceTest` (extend; Mockito only, no Spring context;
tiny budgets — ms-scale sleeps — so the suite stays fast):

- `enrich_whenSlowDomainExceedsBudget_shouldCountFailedAndContinue()`
- `enrich_whenDeadHost_shouldBoundAtBudgetAndContinue()`
- `enrich_whenConcurrentDomains_shouldFinishInWallTimeOfSlowest()`
- `enrich_whenGlobalBudgetExhausted_shouldStopSchedulingAndReportPartial()`

Existing 5 cases keep passing (behavior superset).

### Out of scope (phase 2)

- Enricher-internal changes (robots, contact paths, `max-pages`) — census shows no evidence emails live deeper in this sample.
- Population expansion (detail-fetch coverage / cap raise) — separate spec against `gupy-detail-domains.md`.
- robots escalation — ToS-respecting read-only only (issue #117 non-goal).

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
