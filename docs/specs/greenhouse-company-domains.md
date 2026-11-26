# Greenhouse Company Domains — Spec

Issue: #117 (continuation) · Status: **implemented**

## 1. Context

#117 asked whether the ~0% email hit-rate was a defect in the enrichment strategy.
The answer recorded in the issue: **97% of the jobs lacking an email never had a
`company_website`**, so `CompanySiteEnricher` had no target. `company_website` is
populated only for Gupy (`gupy-detail-domains.md` §2), and the Gupy backfill returns
`filled=0` on its tail because those detail pages carry no company link.

Measured 2026-11-19, live bot DB, 30-day window (703 jobs):

| source | jobs | `company_website` | `contact_email` |
|---|---|---|---|
| infojobs | 264 | 0 | 3 |
| greenhouse | 231 | 0 | 32 |
| linkedin | 94 | 0 | 5 |
| ashby | 38 | 0 | 0 |
| lever | 33 | 0 | 0 |
| gupy | 30 | **25** | 5 |
| linkedin-post | 11 | 0 | 11 |
| github | 2 | 0 | 1 |

Detail-page probe (plain HTTP GET, one page per source, 2026-11-19):

| source | company link on the job page | verdict |
|---|---|---|
| **greenhouse** | `wellhub.com`, `canonical.com` | **viable** |
| infojobs | `privacy-center.org`, `pandape.com.br`, `apps.apple.com` only | dead |
| ashby | `cdn.ashbyprd.com` only | dead |
| lever | not probed | unverified, low volume |
| linkedin | not probed | out (anti-bot) |

Greenhouse is the only source where the enrichment input can be created: +231 fresh
rows, i.e. coverage 25/703 → ~256/703.

## 2. The grouping trap (why this is not just "call the resolver")

`HttpCompanyDomainResolver` groups by career-page **host**, because Gupy gives each
company its own subdomain (`3coracoes.gupy.io`). Greenhouse does the opposite: every
company lives under one host (`job-boards.greenhouse.io`) and is discriminated by the
**first path segment**. Reusing the current grouping verbatim would fetch one page and
stamp that company's domain onto every greenhouse row in the batch.

## 3. Design

### 3.1 Port

Replace `List<String>` with `List<JobRef>`:

```java
public record JobRef(String url, String company, String source) {}

Map<String, String> resolveCompanyWebsites(List<JobRef> jobs, int maxHosts);
```

Keep a `default` overload taking `List<String>` (source `gupy`, company `null`) so the
two existing call sites (`GupyProvider`, `GupyMcpProvider`) compile unchanged.

### 3.2 Grouping key

`GroupingKey.of(JobRef)`:

- `greenhouse` → `host + "/" + firstPathSegment` → `job-boards.greenhouse.io/gympass`
- everything else → `host` (current behavior)

One fetch per key, first-seen order, `maxHosts` caps distinct keys. The response stays
keyed by the original job URL.

### 3.3 Link policy — greenhouse uses name affinity

The existing policy (first eligible link after the exclusion list) is unsafe here: on
Canonical's page the first eligible link is `ubuntu.com`, not `canonical.com`.

1. Collect eligible links (existing exclusion list, extended with ATS/tracker hosts:
   `greenhouse.io`, `greenhouse.com`, `ashbyprd.com`, `pandape.com.br`,
   `privacy-center.org`).
2. Normalize the company name into tokens (lowercase, strip accents, drop corporate
   suffixes `ltda`, `s.a.`, `inc`, `ltd`, `gmbh`, `do brasil`).
3. Score each candidate host by token overlap against its registrable domain and
   labels.
4. Pick the highest score; tie-break by **fewest host labels** (root domain wins:
   `canonical.com` over `www.canonical.com`, `stripe.com` over `docs.stripe.com`);
   **require ≥ 1 shared token**.
5. No shared token → skip the key (no metadata). Never fall back to "first link".

The company name is already stored (`RawJob.company`, with `" - <source>"` suffixes
stripped by `GreenhouseProvider.stripSuffix`), so no new data is needed.

### 3.4 Fetch path

`GreenhouseProvider` resolves websites for the page it is about to return and attaches
`metadata.companyWebsite`, mirroring `GupyProvider.resolveCompanyDomains`. New config
`greenhouse.max-detail-domains` (default `100`, overflow logged and skipped).

### 3.5 Backfill

`BackfillCompanyWebsitesService` hardcodes `SOURCE = "gupy"`. Add a source parameter:

```
POST /api/jobs/backfill-websites?source=greenhouse&dryRun=true|false&maxHosts=&afterId=
```

Default `source=gupy` keeps current behavior. Unchanged: `dryRun` defaults to `true`,
only null→found transitions are written, `afterId` paging, idempotent and rerunnable.

## 4. Tests (TDD, WireMock, no Spring context in unit tests)

| test | expectation |
|---|---|
| `resolve_twoGreenhouseJobsDifferentBoards_oneFetchPerBoard` | exactly 2 detail fetches; each domain on its own job |
| `resolve_companyNameMatchesOneCandidate_picksByName` | `ubuntu.com` + `canonical.com`, company "Canonical" → `canonical.com` |
| `resolve_noTokenOverlap_noMetadata` | nothing set, never the first link |
| `backfill_apply_neverOverwritesExistingWebsite` | existing value untouched |
| `backfill_sourceGreenhouse_scansOnlyGreenhouseRows` | gupy rows untouched |
| `backfill_dryRun_writesNothing` | zero saves |
| `resolve_gupyJobs_stillGroupedByHost` | regression: current behavior preserved |

## 5. Acceptance criteria

- Dry-run on the 30d greenhouse cohort: record `scanned / filled / stillNull` here.
- `company_website` coverage in the 30d window: 25/703 → record the number after apply.
- One enrich run afterwards: record new `contact_email` count.
- Full suite green.

**Expectation, stated before any code:** coverage should jump ~10x; email yield should
not. On the Gupy population (Brazilian companies) ~12% of resolved domains yielded a
contact. Greenhouse rows are international companies hiring in Brazil (#127), so their
sites are less likely to expose a Brazilian HR inbox. **Single-digit new emails per
month is the realistic ceiling.** If the enrich sample returns 0, the conclusion is
that #117's remaining lever is coverage without payoff — record it and stop.

## 6. Out of scope

- infojobs / ashby / linkedin / lever resolution (probed dead or out of bounds).
- `EmailExtractor` changes, email sending, candidate-email inference.
- Re-running the Gupy backfill (measured: `filled=0`).
