# LinkedIn enrichment pacing (issue #99 follow-up)

Single-owner scope. Service mode (`scraper.linkedin.mode: service`, the live
path) only; jsoup fallback keeps current behavior (follow-up if needed).

## Problem

Detail enrichment hits LinkedIn guest rate limits (429 storm, one per job),
and each 429 is swallowed (warn + keep unenriched) with no retry, no
backoff, no pacing state. `ExponentialBackoffRetry` and `TokenBucketRateLimiter`
already exist as beans and travel registered per provider entry, but neither
is ever invoked on the LinkedIn path — the only throttle is a fixed 500 ms
sleep. Result: `TimeoutException` on full fetches, ~2 saved per cycle.

## Design (wire the dead wiring, invent nothing)

- `LinkedInScraperClient` gains `RetryStrategy` + `RateLimiter` constructor
  deps (wired in `AppConfig` from the existing beans, same as other providers
  already receive them in their registry entries).
- Detail call path: `rateLimiter.acquire("linkedin")` before each
  `extractDetail`, then `retry.execute(...)` around it.
  `ExponentialBackoffRetry.isRetryable` already matches `429`/timeout/5xx,
  so no matcher changes. Keep-unenriched fallback stays for exhausted retries.
- LinkedIn-specific limiter tuning WITHOUT touching the shared default:
  use `TokenBucketRateLimiter`'s per-provider overrides map via new yaml keys
  `scraper.linkedin.enrich-permits-per-second` (default 1.0) and
  `scraper.linkedin.enrich-burst` (default 1) — conservative, and tunable
  without rebuild. The fixed 500 ms sleep stays as the floor.
- No change to: search loop/skip-keyword behavior, `orTimeout` budgets,
  quota math, normalizer, save-skip, jsoup provider.
- Known limitation (documented, not fixed here): `orTimeout` bounds the
  reported result, not the running virtual thread — executor close still
  waits. Changing that is a separate, riskier slice.

## Spec touch-ups in the same slice

- `docs/specs/linkedin-scraper-client.md` still claims search 429 throws;
  code skips the keyword. Align the two lines with the code (skip behavior).

## Tests (TDD, RED first; `method_scenario_expected` + `@DisplayName`)

- Detail 429 → retried per existing backoff params, then keep-unenriched
  after exhaustion (WireMock: 429,429,200 → enriched; 429×N → unenriched,
  no throw). No test asserts sleep durations (timing-flaky by policy).
- Limiter invoked once per detail call (Mockito verify, no Spring).
- Constructor/wiring: AppConfig bean test or equivalent asserting the client
  receives both deps.
- Regression: existing skip-keyword, timeout-split, early-dedupe tests stay
  green; full suite green.

## Out of scope

- jsoup `LinkedInProvider` detail-429 rethrow (separate behavior, inactive path).
- Caching layer, keyword tuning (#79), `orTimeout`/executor semantics.
- Changing shared limiter defaults (other providers unaffected).
