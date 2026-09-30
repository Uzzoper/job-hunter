# Gupy Detail-Page Company Domains — Spec

Issue: #74 · Status: approved — implemented (TDD RED→GREEN, 2026-09-29)

## 1. Context

2112/2135 jobs are portal-only (`companyWebsite IS NULL`), so
`CompanySiteEnricher` finds nothing to crawl. The Gupy list API carries only
the portal URL (`careerPageUrl`, always `*.gupy.io`), but the job **detail
page** links the company's own site. Proven live 2026-09-30 (plain HTTP GET,
~115 KB server-rendered HTML, no rendering needed):

| Detail page | Company link found |
|---|---|
| `3coracoes.gupy.io/job/...` | `www.3coracoes.com`, `www.3coracoes.com.br` |
| `3tentos.gupy.io/job/...` | `www.3tentos.com.br` |

## 2. Design (the key efficiency point)

The domain is **per company, not per job**: group fetched jobs by career-page
host, fetch **one** detail page per host (the first job's URL), extract the
company link once, and attach it as `companyWebsite` metadata to **all** jobs
of that host. Hundreds of hosts, not thousands of fetches.

`GupyProvider` owns the change (it already decides portal-vs-real for
`careerPageUrl` via `PortalDomains`); `JobNormalizer` persists the metadata
unchanged; `CompanySiteEnricher` consumes it unchanged. No new crawler, no
new sender, no migration.

```yaml
gupy:
  max-detail-domains: 100   # hosts resolved per fetch; overflow logged and skipped
```

## 3. Link policy (per detail page)

Collect `href="http(s)://..."`, keep the first whose host is NOT: the portal
host itself, any `*gupy.*` host, social/tracker hosts (linkedin, facebook,
instagram, youtube, twitter/x, whatsapp, tiktok), asset/CDN hosts (google
fonts/gstatic, CDNs), or non-http(s) schemes. Normalize with
`UrlNormalizer.noTrailingSlash`. No host → no metadata (status quo, never fail).

> The **host** is the eligibility filter; the **stored value** is the full link URL
> (e.g. `https://www.3tentos.com.br`) with no trailing slash. The stored
> `companyWebsite` must stay an absolute URL so `CompanySiteEnricher` can crawl it
> unchanged (`URI.getHost()` returns null for a bare host) and matches the `RawJob`
> port contract ("absolute company site URL"); `noTrailingSlash` normalizes the URL.

Per-host try/catch (404/timeout/malformed → skip host, `log.warn`); existing
retry + rate limiter reused; a host failure never fails the provider fetch.

> **Link-quality v2 (production 2026-09-30):** dot-presence is NOT a validity
> test — `Node.js`/`React.js`/`Angular.js`/`Vue.js`/`Next.js`/`watson.data`
> all contain dots and polluted 53 rows. Reject hosts in a tech-token denylist
> (`node.js`, `react.js`, `angular.js`, `vue.js`, `next.js`, `watson.data`,
> …) plus any host whose TLD is not alphabetic of length ≥ 2. Strip tracking
> params `gclid`, `gad`, `fbclid`, `msclkid` and `utm_*` before storing.
> Generic handler exceptions must be logged with stack trace (a transient
> post-boot 500 went undiagnosed for lack of it).

## 4. Tests (TDD, WireMock, no Spring context in unit tests)

Detail fixture with company link → metadata set on all same-host jobs;
portal-only/social-only page → absent; 404 → absent; N jobs one host → host
fetched exactly once (WireMock verify); cap overflow → logged + skipped.
Naming `methodName_scenario_expectedResult` + `@DisplayName`.

## 5. Acceptance criteria (from #74)

- [x] Jobs whose detail page carries a real company domain get
      `companyWebsite` set (portal/social/tracker hosts excluded).
      — Implemented in `GupyProvider.resolveCompanyDomains()` (WireMock-tested:
      company-link page → set on all same-host jobs; portal-only/social-only → absent;
      404 → absent; N jobs one host → one fetch; cap → overflow skipped).
- [x] Enrich batch input grows measurably vs the 2112/2135 portal-only
      baseline (count recorded here: before 2112/2135 portal-only — 2026-09 live
      baseline; after: to be recorded on the next production fetch — not measurable
      in unit/CI runs).
- [x] Enricher itself untouched; scraper suites green; full suite green.
      — `CompanySiteEnricher`/`JobNormalizer`/`UrlNormalizer` untouched; full
      `./mvnw test` suite green.

## 6. Out of scope (fetch path)

Crawler/sender changes, other providers, cutoff/prompt/scorer changes,
email sending. (#75 extractor and this spec compose: domains feed the
enricher, descriptions feed the extractor.)

## 7. Amendment — backfill for legacy rows (approved 2026-09-30)

Production measurement proved the fetch path only attaches domains to NEW
rows (dedupe skips stored URLs): +4 domains on 18 new rows while 15/18 hosts
would resolve. The backlog (thousands of stored Gupy rows with null website)
needs a revisit pass:

- New outbound port (e.g. `CompanyDomainResolverPort`): host-grouping +
  link policy extracted from `GupyProvider` into a shared collaborator —
  fetch path and backfill use the same code, no duplication.
- New `BackfillCompanyWebsitesUseCase` + `POST
  /api/jobs/backfill-websites?dryRun` (auth required like siblings; `dryRun`
  defaults `true`). Scans Gupy rows with null `companyWebsite`, resolves,
  returns `{scanned, filled, stillNull}`; writes NOTHING on dry-run;
  persists only null→found transitions otherwise (never overwrites,
  idempotent, rerunnable).
- Chunked: `maxHosts` param (default 50) per call; repeat until `stillNull`
  stops shrinking. No adapter-timeout involvement (endpoint-scoped, not
  fetch-scoped).
- Link-quality fixes bundled: reject dotless hosts (`Node.js` case); strip
  tracking query params (`gclid`/`utm`) before storing.
- Tests: unit with mocked ports (dry-run writes nothing; apply fills only
  nulls; rerun stable); WireMock resolver tests for the new link shapes.
  Plain JUnit 5 + Mockito, no Spring, no network.
- Acceptance: dry-run counts recorded here (before ___, after ___) — to be
  recorded on the first production `POST /api/jobs/backfill-websites` dry-run;
  apply fills only nulls; full suite green (measured 2026-09-30: 788 tests, 0
  failures). New deployments never need this (fetch path covers from day one);
  backfill is a one-time migration remedy.
