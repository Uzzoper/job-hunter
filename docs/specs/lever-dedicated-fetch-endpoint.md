# Lever dedicated fetch endpoint

> Status: implemented in `feat/lever-dedicated-fetch` (base `main`, PR to `main`).
> Origin: post-merge follow-up of PR #103 / fase A validation (2026-11-03).

## Context

The global `POST /api/jobs/fetch` runs providers in parallel through
`ProviderBasedScraperAdapter`, each with its own ~120 s per-provider cap
(lever included), heavy providers routinely blow that cap inside the global
run — VM evidence 2026-11-02/03 shows lever `fetched 0` / `error:
TimeoutException` on every global run (greenhouse completed its own 300 s
ceil) while the DB grows for every other source. Unlike gupy, linkedin and
infojobs, lever has **no dedicated endpoint**, which makes the channel
silently dead: new postings never reach the database and no error is
surfaced beyond `perProvider[].error`. Evidence dated 2026-11-02/03: lever
held at 79 rows while the DB grew for every other source.

## Scope

- `POST /api/jobs/fetch/lever` — a controller endpoint mirroring the existing
  `gupy`/`linkedin`/`infojobs` trio: delegates to
  `FetchSourceJobsUseCase.fetchAndSave(userId, "lever")`, returns `200` with a
  `FetchResultResponse` (500 on service throw is asserted as the controller
  contract only — in production `FetchSourceJobsService` catches provider
  failures into `perProvider[].error`, so a dead provider still answers 200).
- Pure wiring change; no provider, scraper, timeout or pacing code is touched.

## Scenarios

1. **Request accepted** — `fetchLeverJobs_whenValidRequest_shouldReturnSavedJobCount`
   authenticated request returns `200` `totalFetched/totalSaved/perProvider`
   with `perProvider[0].source == "lever"`, and verifies the use case received
   `userId` + `"lever"`.
2. **Service failure** — `fetchLeverJobs_whenServiceFails_shouldReturnError`
   use case throws → `500` with the standard error envelope, same as the
   linkedin path.

## Out of scope

- Global budget/timeout retuning, sequencing or pacing changes.
- Dedicated endpoints for greenhouse/ashby/github (they complete inside the
  global run today; revisit only if evidence shows otherwise).
- Scheduled/auto fetch — interface stays manual, run by the owner's bot.

## Validation (2026-11-04, VM)

- Deploy of `main` `24fc83c` (HEAD confirmed by the bot): `POST
  /api/jobs/fetch/lever` answers the `FetchResultResponse` JSON. The "No
  static resource" 500 observed on Nov 3 was a stale pre-#105 jar, not
  missing code — the signature matched this work's own RED run exactly.
- Two consecutive runs: `fetched 383 / saved 0` both times with
  `detailSkippedCount=0`, `detailFailedCount=0`, `error=null`; URL dedupe
  verified (second run also 0 saved). `SELECT COUNT(*) WHERE
  source='lever'`: 82 before and after.
- The spec's first-run `saved > 0` expectation did NOT reproduce —
  adjudicated healthy, expectation was wrong: the filter-admitted set is
  stable (~82 of 383 postings pass the user's keyword/junior filter; the
  rest are dropped by the filter, not deduped), and the accused backlog had
  already been drained between Nov 2 and Nov 4 by intermediate runs (rows
  79 → 82; hypothesis most plausibly the old-jar discovery round's generic
  fetch). Falsifier on record: `SELECT MAX(created_at) FROM jobs WHERE
  source='lever'` should return a Nov 3/4 timestamp.
- Channel no longer "silently dead": deterministic on-demand access; future
  deltas appear as soon as new postings match the filter.
