# Early dedupe: skip enrichment for already-known URLs (issue #99 follow-up)

Single-owner scope. Supplements (does not replace) enrichment pacing work.

## Problem

Enrichment (detail-page HTTP per job: description/email/company-domain) runs
for every fetched job, then `existsByUrl` drops the already-known ones
(`FetchJobsService.java:53`, `FetchSourceJobsService.java:65`). With ~5k
stored jobs, most enrichment traffic re-details known jobs — pure waste that
also attracts LinkedIn 429 throttling. Enrichment lives inside each
provider's `extract()`, before normalization and before any DB check.

## Goal

Skip the enrichment HTTP for URLs already in the DB, keeping downstream
results byte-identical (same jobs returned, same counts, same saves).

## Design (established pattern: resolve once in service, pass down immutable)

Search already yields URLs before any enrichment (per-provider search loop
runs first, enrichment loop second — verified per provider). Thread a
`Predicate<String> isKnownUrl` down the fetch path:

- `FetchJobsService`/`FetchSourceJobsService` pass
  `jobRepository::existsByUrl` (method ref — no new repository method; the
  per-URL lookup is the status-quo cost, local indexed SQLite).
- `ScraperPort.fetch(keywords, isKnownUrl)`,
  `SourceFetchPort.fetch(sourceId, keywords, isKnownUrl)`,
  `ExtractionStrategy.extract(keywords, isKnownUrl)`.
- Each enrichment site: `if (isKnownUrl.test(url))` skip the detail HTTP
  but still return the basic (unenriched) job, so normalizer counts,
  `perProvider` stats, merge/dedupe and `existsByUrl` save-skip behave
  exactly as today.
- Per-host enrichment (Gupy company domains, `resolveCompanyDomains`):
  skip a host iff EVERY job URL of that host tests known; otherwise resolve
  as today. Deterministic; document the approximation.
- Default/compat shims: keep arg-less overloads delegating with
  `url -> false` (never skip) so non-keyword providers and existing callers
  compile unchanged.

## Non-goals

- No batch URL lookup port method (unnecessary — predicate reuses the
  existing per-URL query).
- No change to normalizer, quota math, merge/dedupe, save-skip, yaml keys.
- No enrichment pacing/backoff/retry changes (separate issue #99 work).
- No behavior change when the predicate reports unknown for all URLs.

## Tests (TDD, RED first; `method_scenario_expected` + `@DisplayName`)

- Provider level (Mockito/WireMock where established): known URL →
  zero detail HTTP calls, basic job still returned; unknown URL →
  enriched exactly as today. Cover LinkedInScraperClient (extractDetail),
  GupyMcpProvider (get_job_by_id fallback), InfoJobsProvider
  (enrichWithDetails incl. maxDetailFetch interplay), GupyProvider
  (per-host all-known skip vs partial resolve).
- Service level: predicate wired from `jobRepository::existsByUrl`
  (Mockito, no Spring).
- Regression: full suite green; unset-keywords fetch byte-identical.

## Out of scope

- Caching layer (Caffeine/Redis) — the DB seen-store + this skip covers it.
- Seniority filtering, keyword tuning (#79).
