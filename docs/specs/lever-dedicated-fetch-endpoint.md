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
