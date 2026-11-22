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
- LinkedIn's guest search page returns ~25 cards per query, and the scraper
  service paginates up to 6 rounds (`linkedin-scraper/src/scrapers/search.ts`,
  `MAX_PAGINATION_ROUNDS`);
- the run observed **48 fetched / 19 saved**, which matches 10 keywords × ~5–6
  useful unique cards.

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
4. **Keyword guidance (profile, not code).** Data terms should be Portuguese
   compounds (`analista de dados junior`, `analista de bi junior`) plus
   `data analyst` / `bi analyst`; bare `sql` is dropped because it matches the
   description of every dev listing and pollutes the keyword gate.

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
  visibility ceiling (~60 cards).
- Keyword mining or automatic term expansion.

## 6. Validation (post-merge, live)

Unit tests prove the cap mechanics; they cannot prove that the supply grows.
The live comparison must be captured on the deployed build, with the same
runtime keyword list on both sides.

**Frozen keyword list.** The comparison is only valid with identical terms on
both sides. The baseline run used 10 runtime terms (4 dev + 6 data); the exact
strings live in the bot profile (`GET /api/profile` →
`preferences.keywords`) and MUST be recorded here before the after-run.
`application.yaml`'s default list is NOT what the bot uses while runtime
preferences are set (`DefaultKeywordResolver` returns the stored list).

Frozen list: _to be captured before the after-run_.

Baseline — 2026-11-22, deployed build, 10 runtime keywords, `max-jobs: 60`,
`past_week`:

| metric | baseline | after |
|---|---|---|
| `totalFetched` (post-quota) | 48 | _pending_ |
| `totalSaved` (new URLs) | 19 | _pending_ |
| data-track titles in the DB | 9 | _pending_ |
| data-track junior + remote + BR (page-checked) | 1 (`6828`) | _pending_ |
| per-keyword run counts (status endpoint) | not recorded | _pending_ |

Protocol after the build is deployed:

1. Set the same runtime keywords (4 dev + 6 data) and run one async LinkedIn
   fetch; record `totalFetched`, `totalSaved`, per-keyword runs.
2. List every new data-track title, page-check remote + Brazil + junior, and
   count the eligible ones.
3. Diff against the baseline table above and record the result here.

Caveats:

- LinkedIn supply drifts daily; the comparison is directional, not controlled.
- `totalSaved` is biased by URL dedupe — a second run only saves genuinely new
  URLs, so judge coverage by `totalFetched` and new titles, not by `saved`.
- Per-keyword `jobsFound` is the raw batch size, not the kept per-keyword
  limit; the kept count is only observable through `totalFetched`.

Decision gate: if fetched volume does not grow materially (roughly 2x or more)
or the extra cards add no junior+remote+BR data supply, revisit the guest
ceiling assumption before tuning anything else.
