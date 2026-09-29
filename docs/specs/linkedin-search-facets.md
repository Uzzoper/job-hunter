# Spec: LinkedIn Search Facets (work-type / seniority / time-range)

> **Layer:** `infrastructure` (+ `linkedin-scraper` Node side)
> **Implementation files:** `.../scraper/client/LinkedInScraperClient.java`,
> `linkedin-scraper/src/routes/jobs.ts`, `linkedin-scraper/src/scrapers/search.ts`
> **Tests:** `LinkedInScraperClientTest.java`, `linkedin-scraper/tests/scrapers/search.test.ts`,
> `linkedin-scraper/tests/routes/jobs.test.ts`

---

## Context

Production validation (2026-09-28, branch `dev` @ `ab4be70`) measured the real
eligibility funnel for a remote-only junior candidate:

| Stage | Count |
|---|---|
| LinkedIn jobs in DB | 136 |
| Junior/intern in title (grep) | 30 |
| …and actually a dev role | 17 |
| …and actually remote (verified one-by-one) | **2** |

Two of the three filters in `application.yaml` are **decorative** — they are read
into `LinkedInScraperProperties` but never reach the LinkedIn search URL:

| Property | Reaches the search URL? | Evidence |
|---|---|---|
| `geo-ids` | ✅ yes | `LinkedInScraperClient.java` URI builder |
| `keywords` | ✅ yes | same |
| `locations` | ✅ yes | same |
| `work-type` | ❌ **no** | `routes/jobs.ts` reads only keywords/location/geoId; `search.ts` maps none |
| `seniority` | ❌ **no** | same |
| `time-range` | ❌ **no** | same |

Consequence: LinkedIn returns its default relevance ranking (senior-heavy, all
work models), and remote-vs-onsite is decided afterwards by text-matching the
job description. That classifier is unreliable — in the same run it marked
*QuantumBlack Junior SWE* as remote when the job page actually says
**Presencial**. Filtering server-side removes whole classes of false positives
before they ever reach the normalizer.

## Amendment A — numeric codes MUST be verified live before freezing (binding)

LinkedIn's native facet codes (e.g. `f_WT=2` for remote, the entry-level
`f_E` code, the past-month `f_TPR`/`f_TE` code) are undocumented and drift.
A wrong code filters silently-wrong — worse than no filter. Before
implementation, probe the live guest search (`curl` comparable to the
`AtsProbe` pattern): same query with and without each facet, assert the
returned cards actually honor it (remote-only results, entry-level titles,
recent dates). Record the verified codes below; implementation hardcodes
only verified codes. If a code cannot be verified live, that facet ships as
pass-through (forwarded but unmapped) with a `log.warn`, never as a guess.

Verified codes (live guest SSR probes, 2026-09-28, base query
`https://www.linkedin.com/jobs/search/?keywords=desenvolvedor%20junior&location=Brazil`):

| Facet | Configured value | Native code | Verdict | Probe evidence (cards / dates) |
|---|---|---|---|---|
| time-range | `past_month` | `f_TPR=r2592000` | ✅ verified | `+ &f_TPR=r2592000` → 60 cards, 20 distinct `datetime` values, **all within 31 days** (baseline: 32 values including `2025-03-28`); result-set md5 differs from baseline |
| time-range | `past_week` | `f_TPR=r604800` | ✅ verified | `+ &f_TPR=r604800` → 60 cards, 7 distinct `datetime` values, **all within 7 days** |
| time-range | `past_day` | `f_TPR=r86400` | ✅ verified | `+ &f_TPR=r86400` → 60 cards, 2 distinct `datetime` values (`2026-09-28`/`29`) |
| work-type | `remote` | `f_WT=2` | ❌ **not honored** | byte-identical 60-card set vs baseline for `f_WT=2`, `f_WT=2&f_WT=3`, `&refresh=true`, `&sortBy=DD`, with and without `geoId=106057199`, and on a second keyword (`node%20junior`) |
| seniority | `entry_level` | `f_E=2` | ❌ **not honored** | byte-identical 60-card set vs baseline for `f_E=2` and `f_E=1` (internship control), alone and combined with `f_TPR` |

Consequence: only the time-range codes are hardcoded. `work-type` and
`seniority` ship as pass-through (Java forwards their values; Node warns with
`log.warn` and never appends unverified `f_WT`/`f_E` params).

## Amendment B — narrow the work-type default (binding)

`work-type: "remote,hybrid,on-site"` Fermata everything: forwarding all three
values is a no-op filter. Change the committed default to `"remote,hybrid"`
(the eliminatory gate already rejects onsite downstream, so nothing eligible
is lost). `seniority: "entry_level"` and `time-range` stay as configured.

---

## Expected behavior

### Scenario 1: work-type facet is forwarded to the search URL
- **GIVEN** `scraper.linkedin.work-type: "remote"` is configured
- **WHEN** `extract()` builds the search URI
- **THEN** the URI contains `workType=remote` as a query parameter
- **AND** the Node route reads `workType` from `req.query`

### Scenario 2: seniority facet is forwarded to the search URL
- **GIVEN** `scraper.linkedin.seniority: "entry_level"` is configured
- **WHEN** `extract()` builds the search URI
- **THEN** the URI contains `seniority=entry_level` as a query parameter
- **AND** the Node route reads `seniority` from `req.query`

### Scenario 3: time-range facet is forwarded to the search URL
- **GIVEN** `scraper.linkedin.time-range: "past_month"` is configured
- **WHEN** `extract()` builds the search URI
- **THEN** the URI contains `timeRange=past_month` as a query parameter
- **AND** the Node route reads `timeRange` from `req.query`

### Scenario 4: Node maps facets to LinkedIn's own filter params
- **GIVEN** a search request with verified facet values
- **WHEN** `search.ts` builds the LinkedIn `jobs/search` URL
- **THEN** the URL contains the verified native codes (Amendment A table)
- **AND** an unconfigured facet adds no parameter at all

### Scenario 5: facets are blank by default and change nothing
- **GIVEN** `work-type`, `seniority` and `time-range` are all empty/blank
- **WHEN** `extract()` builds the search URI
- **THEN** none of the three query parameters appear
- **AND** the search behaves exactly as before this spec (backward compatible)

### Scenario 6: multi-value facets are comma-joined
- **GIVEN** `work-type: "remote,hybrid"`
- **WHEN** `extract()` builds the search URI
- **THEN** the URI contains a single `workType=remote,hybrid` parameter
- **AND** Node splits it into the native multi-value form LinkedIn expects
  (repeated `f_WT` params or comma form — whichever verifies live)

---

## Acceptance criteria

- [ ] Amendment A table filled with live-verified codes (probe evidence
      recorded: query URLs + result counts honoring each facet).
- [ ] JUnit (WireMock URI assertions incl. facet params, blank-default
      no-op, multi-value join) + Jest (route query reading, URL builder
      mapping incl. unconfigured-adds-nothing) green, TDD RED→GREEN.
- [ ] Full `./mvnw test` + `linkedin-scraper` `npm test` + `tsc` green.
- [ ] No behavior change when facets blank; existing suites untouched.
- [ ] `work-type` default narrowed to `remote,hybrid` (Amendment B).

## Out of scope

Logged-in LinkedIn session, quota/cap changes, other providers, cutoff/
prompt/scorer changes, new endpoints.
