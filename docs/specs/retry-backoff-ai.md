# Spec: Retry with backoff for AI calls

## Problem

OpenRouter free-tier rate limits (HTTP 429) and transient 5xx/network errors surface
directly to users as `502 Bad Gateway` on analyze / email / resume-tailoring requests.
Observed twice in a single session against `poolside/laguna-s-2.1:free`.

The codebase already has `ExponentialBackoffRetry` (used by all scraping providers),
and `OpenRouterClient` accepts it in its constructor — but **never stores or uses it**
(dead parameter).

## Approach

Wire the existing retry into `OpenRouterClient` and minimally generalize its
retryability detection. No new dependencies, no new config keys.

### Changes

1. **`OpenRouterClient`** (`infrastructure/ai/`)
   - Store the injected `ExponentialBackoffRetry` as a field.
   - `complete()`: when a retry policy is present, wrap the HTTP call in
     `retryPolicy.execute(...)`. On exhaustion the policy throws `ScraperException`;
     translate it to `AiException("AI call failed after retries", e)` so `AiPort`
     semantics are preserved for callers and `GlobalExceptionHandler`.
   - When the policy is `null`, behave exactly as today (backwards compatible with
     existing unit tests).

2. **`ExponentialBackoffRetry.isRetryable`** (`infrastructure/scraper/retry/`)
   - Extend the message-token check ("timeout", "429", "500", "502", "503", "5xx")
     to ANY exception type, not only `ScraperException`. This makes
     `AiException("HTTP error: 429 ...")` retryable while keeping provider behavior
     unchanged (their messages still match).
   - Timeout detection via cause chain stays as-is.

3. **Config**: reuse `scraper.retry.*` properties (already wired via the
   `exponentialBackoffRetry` bean in `AppConfig`). Documented note: these now also
   govern AI calls.

### Retryable vs fail-fast

| Outcome | Behavior |
|---|---|
| HTTP 429 / 500 / 502 / 503 / generic 5xx | Retried with exponential backoff + jitter |
| Network timeouts (`SocketTimeoutException` anywhere in cause chain) | Retried |
| HTTP 401 (invalid key) / 402 (credits) / 400 | Fail fast — retrying cannot help |

Worst-case added latency ≈ sum of backoffs (seconds), bounded by `max-delay-millis`.

## Tests (TDD)

### `ExponentialBackoffRetryTest` additions
- message-based retryability applies to non-`ScraperException` types
  (e.g., `RuntimeException("HTTP error: 429 ...")` is retried)
- exhaustion after repeated retryable failures throws `ScraperException`

### `OpenRouterClientTest` additions (WireMock)
- `429` then `200` → returns completion; exactly 2 calls hit the server
- always `429` → `AiException` whose message mentions retries exhausted
- `401` → fails fast with exactly 1 call
- request timeout (delayed response) → retried

## Out of scope

- Provider scraping behavior (already retried; unchanged)
- New config keys or spring-retry dependency
- CLI/TUI changes (they already surface the resulting errors)
