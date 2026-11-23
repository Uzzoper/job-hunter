# LinkedIn search coverage — per-keyword budget and 30-day window

Status: **in review** · Issue: #165 · PR: #164 · Branch: `feat/linkedin-search-coverage`

## 1. Context

`LinkedInScraperClient` runs one microservice search per configured keyword and
merges results by URL up to `scraper.linkedin.max-jobs`. Since the fair-share
change (P1-4), each keyword is capped at

```
perKeywordQuota = ceil(max-jobs / keywords.size())
```

Measured 2026-11-22, live probe with 10 runtime keywords (4 dev + 6 data) and
`max-jobs: 60`:

- per-keyword quota = `ceil(60 / 10)` = **6**;
- the scraper service paginates up to 6 rounds
  (`linkedin-scraper/src/scrapers/search.ts`, `MAX_PAGINATION_ROUNDS`). Measured
  on 2026-11-22 (§6.1): it returned **120** cards for `desenvolvedor junior`
  and **109** for `estagio desenvolvedor`. The earlier "~25 cards per query"
  figure was an underestimate — the quota discarded even more than assumed;
- the run observed **48 fetched / 19 saved**, i.e. below the 60 the quota allows,
  because only part of the 10 terms produced cards.

The fair-share formula fixed a real defect (the first fat keyword starving the
rest), but it couples the per-keyword budget to the number of keywords: every
term added to the profile shrinks every other term's coverage. That is
backwards for multi-track sourcing (dev + data), where more terms should mean
more coverage, not less.

Second mismatch: `scraper.linkedin.time-range` is `past_week`, while the funnel
policy and the normalizer both accept 30 days (`scraper.normalizer.max-age-days:
90`). LinkedIn's native `f_TPR=r2592000` is a verified code (Amendment A of
`linkedin-search-facets.md`), so the search is discarding valid 8–30 day
postings before the funnel ever sees them.

One assumption checked and corrected: the LinkedIn normalizer is built with
`scraper.linkedin.keywords` as its constructor default, but
`FetchSourceJobsService` passes the resolved runtime list to
`JobNormalizer.normalize(raw, keywordOverride)`. The keyword gate therefore
sees the runtime terms, not the dev-only yaml list — it was not dropping data
listings. The per-keyword cap was the coverage bottleneck.

## 2. Problem

Two independent settings make the LinkedIn search shallower than the source

- **Per-keyword quota shrinks with keyword count.** 10 keywords → 6 jobs each,
  regardless of how many the query returned.
- **Time window is tighter than the policy.** `past_week` drops listings the
  funnel would accept.

Facets are not part of the problem: `f_E` (seniority) and `f_WT` (work type) are
not honored by the guest SSR page and are documented as pass-through. Seniority
and work model remain downstream filters.

## 3. Proposal

1. **Decouple the per-keyword budget from the keyword count.** Add
   `scraper.linkedin.per-keyword-limit` (default 25). The loop keeps at most
   that many unique jobs per keyword. `0` or absent keeps the derived
   `ceil(max-jobs / keywords.size())` behavior, so existing deployments and
   tests remain valid.
2. **Raise the global cap.** `max-jobs: 150`, so 10 keywords × 25 can actually
   contribute (subject to URL overlap). The global cap and the early stop are
   unchanged.
3. **Align the window with the policy.** `time-range: past_month`
   (`f_TPR=r2592000`), matching the 30-day funnel rule; the normalizer still
   drops anything older than its own `max-age-days`.
4. **Keyword guidance (profile, not code).** Junior floor: internship terms are
   out (owner does not apply to estágio, except a big-company one, which the
   search does not target) — `estagio desenvolvedor` was dropped from the
   default list. Dev terms are verified compounds (`desenvolvedor junior`,
   `desenvolvedor backend junior`, `desenvolvedor java junior`,
   `desenvolvedor full stack junior`); dead terms that returned 0 cards
   (`dev junior`, `programador junior`) were removed. Data terms should be
   Portuguese compounds (`analista de dados junior`, `analista de bi junior`)
   plus `data analyst` / `bi analyst`; bare `sql` is dropped because it
   matches the description of every dev listing and pollutes the keyword gate.
   The same junior floor was applied to the Gupy and InfoJobs keyword lists
   (removed `estágio`).

The documented default keyword list in `application.yaml` mirrors that guidance;
runtime preferences still win (`DefaultKeywordResolver`).

## 4. Acceptance criteria

- [ ] With 10 keywords, `per-keyword-limit: 25` and `max-jobs: 150`, the first
      keyword can contribute up to 25 jobs (not `ceil(150/10) = 15`).
- [ ] The merged total never exceeds `max-jobs`; the loop still stops querying
      once the cap is reached.
- [ ] A keyword returning fewer jobs than its limit contributes all of them.
- [ ] URL dedupe across keywords is unchanged.
- [ ] `per-keyword-limit` absent or `0` reproduces the derived quota.
- [ ] `LinkedInScraperClientTest` covers the count-independence case (RED first)
      and all previous quota scenarios.
- [ ] `application.yaml` documents the new property, the 30-day window and the
      updated keyword guidance.

## 5. Non-goals

- Logged-in LinkedIn sessions and native `f_E`/`f_WT` facets — still unverified
  on the guest page; out of scope.
- Changing the microservice pagination (`MAX_PAGINATION_ROUNDS`) or the guest
  visibility ceiling — measured at least 120 cards per productive search
  (2026-11-22), higher than the ~60 previously assumed.
- Keyword mining or automatic term expansion.

## 6. Validation (post-merge, live)

Unit tests prove the cap mechanics; they cannot prove that the supply grows.
The live comparison must be captured on the deployed build, with the same
runtime keyword list on both sides.

**Frozen keyword list.** The comparison is only valid with identical terms on
both sides. `application.yaml`'s default list is NOT what the bot uses while
runtime preferences are set (`DefaultKeywordResolver` returns the stored list),
so the lists were read from the bot profile on 2026-11-22 and are frozen here.
The canonical lists for the A/B are the **new** ones (junior floor); the old
lists are kept below for reference only.

Dev-only list, 4 terms (canonical, used by the §6.1 baseline):

```
desenvolvedor junior, desenvolvedor backend junior,
desenvolvedor java junior, desenvolvedor full stack junior
```

Mixed list, 9 terms (canonical, used by the §6.2 baseline):

```
desenvolvedor junior, desenvolvedor backend junior,
desenvolvedor java junior, desenvolvedor full stack junior,
analista de dados junior, analista de bi junior,
data analyst, bi analyst, power bi
```

Historical (superseded, pre-junior-floor):

```
Mixed: desenvolvedor junior, dev junior, programador junior, estagio desenvolvedor,
       analista de dados junior, analista de bi, data analyst, power bi, sql,
       business intelligence
Dev:   desenvolvedor junior, dev junior, programador junior, estagio desenvolvedor
```

### 6.1 Dev-only A/B (primary)

Dev is the saturated track: most fetched URLs are already in the database, so
`saved` is near zero on both sides and cannot measure coverage. The metric is
`fetched` — the client returns every unique job **after the per-keyword quota**
and before URL dedupe (`isKnownUrl` only skips detail enrichment) — together
with the per-keyword `jobsFound` reported by the service.

**Canonical baseline — new list, captured on the deployed build (`main`,
`max-jobs: 60`, `past_week`), 2026-11-22 23:57:30Z → 2026-11-23 00:07:23Z
(9m53s):**

| metric | dev-only before | dev-only after |
|---|---|---|
| keywords | 4 (frozen: desenvolvedor junior, desenvolvedor backend junior, desenvolvedor java junior, desenvolvedor full stack junior) | same |
| raw cards returned by the service | 340 | _pending_ |
| `fetched` (post-quota) | **45** | _pending_ |
| `saved` (new only) | **0** | _pending_ |
| `detailSkipped` | 0 (structural, see below) | — |

Per-keyword baseline:

| term | status | `jobsFound` | kept (quota 15) |
|---|---|---|---|
| `desenvolvedor junior` | ok | 120 | 15 |
| `desenvolvedor backend junior` | ok | 120 | 15 |
| `desenvolvedor java junior` | ok | 100 | 15 |
| `desenvolvedor full stack junior` | ok | 0 | 0 |

Mechanics: derived quota = `ceil(60 / 4)` = **15** per keyword; three of the
four terms produced cards, so `fetched = 15 + 15 + 15 + 0 = 45`. The service
offered **340** cards and the client kept **45** — 87% discarded by the quota.
`desenvolvedor full stack junior` returned 0 on `past_week` even though a direct
probe on `past_month` had returned 60; the term is kept pending one more run
(supply may be week-sensitive).

Expected after: three productive terms × `per-keyword-limit: 25` = **75** max,
plus `desenvolvedor full stack junior` if the wider window recovers it
(up to 100). The 2× gate was calibrated before this distribution was known; for
the dev-only A/B the target is approaching 75.

**Single-variable caveat.** The "before" runs on the deployed build
(`past_week`, `max-jobs: 60`) and the "after" runs on the new build
(`past_month`, `max-jobs: 150`, `per-keyword-limit: 25`). The comparison
therefore measures the PR's **net** effect, not the quota change in isolation.
Isolating the quota would require an intermediate build; not worth it for this
experiment. If the after-run disappoints, re-run with `past_month` held constant
on both sides before blaming the quota.

`detailSkipped` is not usable for LinkedIn and the status endpoint always
reports 0: only `InfoJobsProvider` sets the `detailSkipped` metadata flag;
`LinkedInScraperClient` skips enrichment for already-known URLs silently
(line 215 of the client). So `saved: 0` cannot distinguish "all URLs already
known" from "fetched but rejected downstream". It does not affect the A/B,
which is measured by `fetched`; closing that instrumentation gap is a separate
follow-up, not a blocker.

Historical (superseded) baseline — old list, kept for reference only:
`desenvolvedor junior` (120), `dev junior` (0), `programador junior` (0),
`estagio desenvolvedor` (109); fetched 30, saved 0, 2026-11-22 22:59:57Z →
23:07:09Z. Not comparable to the after-run (different keyword set).

### 6.2 Mixed run (data track, secondary)

**Baseline superseded (junior floor).** The 48/19 numbers below were captured
with `estagio desenvolvedor` in the list. Re-capture with the new mixed list:

```
desenvolvedor junior, desenvolvedor backend junior,
desenvolvedor java junior, desenvolvedor full stack junior,
analista de dados junior, analista de bi junior,
data analyst, bi analyst, power bi
```

The 2026-11-22 probe (10 mixed keywords, `max-jobs: 60`, `past_week`) is kept
for reference only (previous list):

| metric | mixed baseline | mixed after |
|---|---|---|
| `fetched` (post-quota) | 48 | _pending_ |
| `saved` (new URLs) | 19 | _pending_ |
| data-track titles in the DB | 9 | _pending_ |
| data-track junior + remote + BR (page-checked) | 1 (`6828`) | _pending_ |

Protocol after the build is deployed:

1. Set the frozen mixed keywords and run one async LinkedIn fetch; record
   `fetched`, `saved`, per-keyword runs.
2. List every new data-track title, page-check remote + Brazil + junior, and
   count the eligible ones.
3. Diff against the table above and record the result here.

Caveats:

- LinkedIn supply drifts daily; the comparison is directional, not controlled.
- `saved` is biased by URL dedupe — a second run only saves genuinely new
  URLs, so judge coverage by `fetched` and new titles, not by `saved`.
- Per-keyword `jobsFound` is the raw batch size, not the kept per-keyword
  limit; the kept count is only observable through `fetched`.
- The dev-only baseline run is itself a LinkedIn fetch: it may save a handful
  of new dev URLs and slightly reduce the after-run's `saved`. That is why
  `saved` is not the primary dev metric.

Decision gate:

- Dev-only A/B: the after-run must approach **50** `fetched` (two productive
  terms × 25). A value near 30 means the new limit is not reaching the client.
- Mixed run: with 10 terms and `max-jobs: 150`, the ceiling is 150; a value well
  below the count of productive terms × 25 means something else is capping.
- In both cases, if the extra cards add no junior + remote + BR supply, revisit
  the search-side assumptions before tuning anything else. Raising
  `per-keyword-limit` above 25 is the obvious next lever (the service returns
  ~120 cards for a productive term), but only after this A/B — one variable at
  a time.
