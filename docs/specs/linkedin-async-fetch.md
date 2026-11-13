# LinkedIn async fetch — 202 + poll

> Status: **draft** (issue #116). Spec-first per SDD; TDD for the code.
> Related: #79 Phase 1 (shipped — runtime keywords), #117 (enrichment
> hit-rate), `ProviderBasedScraperAdapter` 180s ceiling, `fase-a-sourcing-restoration`.

## Context (live evidence)

The LinkedIn fetch is already async server-side — the HTTP contract just
does not say so:

- 2026-11-09: client killed `POST /api/jobs/fetch/linkedin` at 120s →
  backend kept saving; 26 jobs landed server-side during the timeout.
- 2026-11-11 (fire-and-poll test): `curl --max-time 5` → HTTP 000 →
  backend completed later (`linkedin: completed - 4 saved`); the bot
  polled `GET /api/jobs` deltas and digested.
- Meanwhile the caller pays the wall clock: hard `orTimeout` at 180s
  (`ProviderBasedScraperAdapter.LINKEDIN_PROVIDER_TIMEOUT`), per-keyword
  searches capped at the (then-)90s read budget, and on 2026-11-11 **3 of
  4 keywords were skipped** (`Read timed out`) with no caller-visible trace.
- 2026-11-12 (first async production run, post-promote): contract verified
  live (202 → 409 → poll `completed` in 6min01s) — but **all 4 keywords**
  hit the 90s search budget → 0 jobs, every term caller-visible as
  `timeout`. Direct microservice measurement with the exact backend query
  (issue #120 diagnostic, read-only): the search returns **HTTP 200 with
  120 jobs in 145.67s** — the search is bounded, the budget was simply too
  small. Fix: `search-timeout-seconds` 90 → 180. Worst-case nightly run
  grows to ~4×150s + detail pacing ≈ 11min; accepted in #120 (the async
  source path has no total ceiling).
- 2026-11-13 (first nightly run on the 180s budget): `completed` in
  14min52s with **21 saved / 2 withEmail** — the volume channel is back.
  But the in-run timings (143–180.4s) put the budget on a knife's edge:
  two keywords finished within 3s of the cut and the broadest first
  keyword timed out at 180.385s → budget raised to 240 (issue #123).

## Problem

1. The bot session blocks 3-5 min (or dies) waiting on data that lands anyway.
2. A skipped keyword is invisible to every client — coverage loss with no log surface beyond the scraper's own WARN.
3. Client timeout ≠ failure semantics are implicit and undocumented.

## Contract

### `POST /api/jobs/fetch/linkedin` → **`202 {"status":"started","startedAt":"…"}`**

- Responds immediately; the fetch+save continues server-side (as it
  already does today).
- **Single-flight**: one LinkedIn fetch at a time. A POST while a run is
  active → `409 {"status":"running","startedAt":"…"}`. Other providers
  keep the synchronous 200 (they complete in seconds; Gupy worst case
  ~100s is still inside client budgets).

### `GET /api/jobs/fetch/linkedin/status`

```json
{
  "state": "idle|running|completed|failed",
  "startedAt": "…", "finishedAt": "…",
  "fetched": 0, "saved": 0, "withEmail": 0,
  "detailFailed": 0, "detailSkipped": 0,
  "error": null,
  "keywords": [
    {"term": "dev junior", "status": "ok|timeout|error", "jobsFound": 12, "durationMs": 8412}
  ]
}
```

- `idle` before the first run since process start; `completed` carries the
  last run's `FetchResult` summary; `failed` carries the error message
  (bounded, same style as `FetchResult`) plus the per-keyword outcomes
  collected before the failure — partial progress stays caller-visible.
- **Per-keyword status kills the silent skip**: every term reports
  `ok|timeout|error` — a skipped keyword becomes caller-visible data.
  Keywords after the `maxJobs` cap-stop (P1-2) are never searched and
  therefore emit no entry — that is the designed guard, not a silent skip.

## Design

- **Registry**: in-memory single-flight registry (one instance — no queue
  infra, single-owner self-hosted). The service records state in
  `try/finally` around the existing `SourceFetchPort.fetch` call, so the
  registry reflects the *actual* outcome, independent of the adapter's
  `orTimeout` completion semantics (evidence: saves continue past client
  death — the registry must not lie about that). The sync use case keeps
  its internal catch-all and surfaces scraper failures as a provider error
  inside a normal `FetchResult`; the async service translates that provider
  error into a `failed` run — a dead fetch must never read as a clean
  completion (review, 2026-11-12).
- **Executor**: dedicated single-thread executor bean; the controller
  submits and returns `202`. No thread-pool growth per request.
- **Keyword listener**: `SourceFetchPort` gains an overload accepting a
  `Consumer<KeywordRun>`; `LinkedInScraperClient`'s existing per-keyword
  loop (P1-1: one transient failure must not discard the rest) emits one
  `KeywordRun(term, status, jobsFound, durationMs)` per term. Other
  providers ignore the listener (or emit a single aggregated run).
- **Auth unchanged**: `X-Bot-Token` filter chain + JWT, as today.

## Non-goals

- No queue/worker persistence (registry is in-memory; restart = `idle`).
- No scraper pacing/timeout reduction — the anti-detection floor is by design.
- No contract change for Gupy/InfoJobs/Lever/Greenhouse/Ashby/GitHub fetches.

## Testing plan (RED before GREEN)

Delivered names (draft names renamed during the 2026-11-12 review):

- `web` (`JobControllerTest`): `fetchLinkedInJobs_whenPost_shouldReturn202WithStarted`,
  `fetchLinkedInJobs_whenAlreadyRunning_shouldReturn409`,
  `fetchLinkedInJobs_whenServiceFails_shouldReturnError`,
  `fetchLinkedInStatus_whenIdle_shouldReturnIdleState`,
  `fetchLinkedInStatus_afterRun_shouldReportCountsAndKeywordRuns`.
- `application` (`LinkedInAsyncFetchServiceTest`): `start_whenIdle_shouldBeginRunAndScheduleFetch`,
  `start_whenAlreadyRunning_shouldRejectWithoutScheduling`,
  `start_whenExecutorRejects_shouldFailRunAndRethrow`,
  `run_whenFetchSucceeds_shouldCompleteRunWithCollectedKeywordRuns`,
  `run_whenProviderReportsError_shouldMarkRunFailedWithKeywordRuns`,
  `run_whenFetchThrows_shouldMarkRunFailed`,
  `run_whenErrorEscapes_shouldReleaseStuckRun`,
  `status_whenQueried_shouldDelegateToStore`;
  plus `InMemoryFetchRunStoreTest` (single-flight transitions, complete/fail
  carrying keyword runs, bounded error, concurrent winner), `FetchSourceJobsServiceTest`
  and `ProviderRegistryTest` (listener forwarding).
- `infrastructure` (WireMock, `LinkedInScraperClientTest`):
  `extract_whenOneKeywordTimesOut_shouldEmitKeywordRunAndKeepOtherKeywordsJobs`,
  `extract_whenSearchSucceeds_shouldReportDurationPerKeyword`.

## Acceptance

The bot fires the fetch without blocking, polls status, and digests when
stable; no keyword is skipped without an explicit, caller-visible
`status` payload entry.
