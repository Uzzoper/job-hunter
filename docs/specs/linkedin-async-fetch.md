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
  searches capped at the 90s read budget, and on 2026-11-11 **3 of 4
  keywords were skipped** (`Read timed out`) with no caller-visible trace.

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
  (bounded, same style as `FetchResult`).
- **Per-keyword status kills the silent skip**: every term reports
  `ok|timeout|error` — a skipped keyword becomes caller-visible data.

## Design

- **Registry**: in-memory single-flight registry (one instance — no queue
  infra, single-owner self-hosted). The service records state in
  `try/finally` around the existing `SourceFetchPort.fetch` call, so the
  registry reflects the *actual* outcome, independent of the adapter's
  `orTimeout` completion semantics (evidence: saves continue past client
  death — the registry must not lie about that).
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

- `web`: `fetchLinkedIn_whenPost_shouldReturn202WithStarted`,
  `fetchLinkedIn_whenAlreadyRunning_shouldReturn409`,
  `fetchLinkedInStatus_whenIdle_shouldReturnIdleState`,
  `fetchLinkedInStatus_afterRun_shouldReportCountsAndKeywordRuns`.
- `application`: `fetchAndSave_whenLinkedInRuns_shouldTransitionRunningToCompleted`,
  `fetchAndSave_whenFetchThrows_shouldMarkFailedWithBoundedError`,
  `fetchAndSave_whenSecondRunRequestedWhileRunning_shouldReject`.
- `infrastructure` (WireMock): `extract_whenOneKeywordTimesOut_shouldEmitKeywordRunAndKeepOtherKeywordsJobs`,
  `extract_shouldReportDurationPerKeyword`.

## Acceptance

The bot fires the fetch without blocking, polls status, and digests when
stable; no keyword is skipped without an explicit, caller-visible
`status` payload entry.
