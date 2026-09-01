# Spec: CLI fetch UX — continuous spinner, progress feedback and cancellable fetch

> **Layer:** CLI (`cli/src`) — no backend changes in v1 (frontend-only)
> **Implementation files:** `cli/src/tui/job_list_screen.rs`, `cli/src/tui/theme.rs`, `cli/src/tui/app.rs`, `cli/src/api.rs` (only if abort/cancel is wired), `cli/src/tui/mod.rs`
> **Corresponding tests:** `cli/src/tui/job_list_screen.rs` (inline unit tests), `cli/src/tui/app_integration_test.rs`, `cli/tests/integration_tests.rs`, `cli/src/tui/theme_test.rs`
> **Related specs:** `docs/specs/cli-tui-spec.md` (Fetch Trigger `f`), `docs/specs/fetch-jobs.md` (backend contract)

---

## Context & Problem

### How fetch works today

`f` / `F` (no modifiers) in the **JobList** screen triggers backend scraping via `POST /api/jobs/fetch`:

```
src/tui/job_list_screen.rs:297  start_fetch()   — sets fetch_in_progress = true (sync, no-op if already true)
src/tui/job_list_screen.rs:311  run_fetch()     — locks ApiClient, calls POST /api/jobs/fetch (600s per-request timeout), then reloads list via fetch_jobs()
src/tui/app.rs:309               fetch_pending path in App::run() — draws one frame, then awaits run_fetch() inline, then continue
src/tui/theme.rs:285             render_loading(frame, area, theme, message) — centered popup with spinner_frame() + message
src/tui/theme.rs:279             spinner_frame() — 10-frame Braille cycle (⠋⠙⠹…) indexed by (now_ms / 100) % 10
src/tui/job_list_screen.rs:164   fetch_in_progress: bool
src/tui/job_list_screen.rs:681   draw_main_content — if fetch_in_progress { render_loading(..., "⟳ Fetching jobs from Gupy, InfoJobs & LinkedIn…"); return; }
```

`POST /api/jobs/fetch` is **synchronous** and long: Gupy + InfoJobs + LinkedIn scrapers run sequentially on the backend, typically **40–90 s** end-to-end (see `cli-tui-spec.md` — Expected Operation Times). The TUI sets a 600 s per-request timeout for this call; the global client timeout (150 s) is not used.

### Observed bug

When the user presses `f`, the loading popup appears for **exactly one frame** and then the screen freezes without feedback until the scrape finishes (30 s+):

1. `start_fetch()` sets `fetch_in_progress = true`.
2. Next iteration of `App::run()` enters the `fetch_pending` branch (`app.rs:310`), calls `terminal.draw()` once (spinner is rendered once), then `await s.run_fetch()` which **blocks the entire event loop** for the duration of the HTTP call.
3. While `run_fetch().await` is pending, the 100 ms `crossterm::event::poll` redraw loop does not run, so `spinner_frame()` is never called again and `render_loading` is not re-drawn — the spinner does not animate, the message does not update, and no input is processed.
4. After the backend answers, the list reloads and a "Fetch completed" toast appears. Any failure during the fetch appears only at the end as "Fetch failed: ...".

Result: the `spinner_frame()` / `render_loading` pair is correct per-frame, but it is invoked only once because the main loop blocks on the synchronous scrape request. The UX reads as "pressed f and nothing happened".

### User impact

- No confidence that fetch is running; users press `f` again or press `q` and kill the process mid-scrape.
- No indication of progress among the three providers (Gupy → InfoJobs → LinkedIn) or remaining time.
- No way to cancel a long or stuck scrape (e.g. LinkedIn Playwright hangs) short of `Ctrl+C` which quits the app.

---

## Goal

Make the `f` fetch flow provide **continuous, trustworthy feedback** without blocking the UI, using a **frontend-only incremental solution** (`v1`) with a clear path to a backend-driven progress API (`v2`).

`v1` (this spec) must fix the spinner freeze, show an estimated provider-phase message, keep the 100 ms poll loop running, and allow cancellation with `Esc`. `v2` (out of scope) may introduce `POST /api/jobs/fetch → { fetchId }` + polling or SSE for real per-provider progress.

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| **FR-1** | **Continuous spinner.** While `fetch_in_progress == true`, the loading popup animates every frame (spinner cycle ≤ 100 ms). | Must |
| **FR-2** | **Single pending guard.** Pressing `f`/`F` while a fetch is already in progress is a no-op (existing behavior preserved). | Must |
| **FR-3** | **Estimated provider-phase message.** While scraping, show which provider is likely active, estimated from elapsed time (e.g. `Scraping Gupy… (1/3)`, `Scraping InfoJobs… (2/3)`, `Scraping LinkedIn… (3/3)`). Each provider is assumed ~10 s; labels update without any backend change. | Must (v1 minimal) |
| **FR-4** | **Elapsed + estimated remaining time.** Show `elapsed / estimated` (e.g. `12s / ~30s`) alongside the provider message. Estimation is heuristic, not a contract. | Must |
| **FR-5** | **Non-blocking redraw loop.** The 100 ms `crossterm::event::poll` loop continues to draw while the fetch runs, so spinner animation, clock, and hotkeys bar remain alive. | Must |
| **FR-6** | **Cancel with `Esc`.** Pressing `Esc` while fetching aborts the in-flight `POST /api/jobs/fetch` (abort the tokio task), clears `fetch_in_progress`, and shows a "Fetch cancelled" toast. The existing list stays as-is (no partial reload). | Must |
| **FR-7** | **Success feedback.** On success, reload the job list through the same path as `r` (refresh), show "Fetch completed — N jobs" toast (include new count delta when available), and clear the loading overlay. | Must |
| **FR-8** | **Failure feedback.** On backend error or timeout, show an error toast with the reason, keep the previous list, and clear the loading overlay. | Must |
| **FR-9** | **Hotkeys bar hint during fetch.** While fetching, the hotkeys bar replaces the normal hints with `[Esc] Cancel fetch` (plus `Ctrl+C` force-quit remains globally). | Should |
| **FR-10** | **Optional real list refresh during fetch.** If feasible with no backend change, poll `GET /api/jobs` every ~5 s during fetch to surface newly scraped jobs early. If not feasible (extra load or cache inconsistency), document as `v2` and keep "reload after completion" for `v1`. | Should (v1 optional, v2 if excluded) |

---

## Non-Functional Requirements

| ID | Requirement | Rationale |
|----|-------------|-----------|
| **NFR-1** | **Input remains responsive.** The TUI must process `Esc` (cancel) and `Ctrl+C` (quit) within one poll interval (≤ 100 ms) even while the fetch HTTP call is in-flight. | Cannot tolerate 30 s input freeze. |
| **NFR-2** | **No CPU spin.** Redraw while fetching must be driven by the existing 100 ms `poll`/`draw` cadence, not a tight loop. Target ≤ 10 fps while loading (matching current `poll(Duration::from_millis(100))`). No extra `tokio::spawn` that busy-loops. | Battery/CPU on laptops. |
| **NFR-3** | **No backend contract change in v1.** `POST /api/jobs/fetch` stays synchronous. No new endpoint, migration, or Spring changes required to land the fix. | Keeps `v1` shippable without backend deploy. |
| **NFR-4** | **Preserve existing timeouts.** Per-request timeout for fetch remains 600 s; global 150 s remains for other calls. Cancellation via `Esc` aborts before timeout. | Consistency with `cli-tui-spec.md` operation times. |
| **NFR-5** | **Theme consistency.** Loading popup uses existing `Theme::style_warn()` / `SPINNER_FRAMES` tokens; no new color constants. Provider-phase message is a `String` argument to `render_loading`, not a new widget type. | Minimal visual diff, good dark-theme contrast already verified. |
| **NFR-6** | **No new dependencies.** Pure `tokio`/`ratatui`/`crossterm` task management; do not add `indicatif`, `async-channel`, etc. | Keeps `cargo build` lean. |

---

## Proposed Implementation

### Summary

Make the `fetch_pending` path in `App::run` **non-blocking**: spawn the scrape into a `tokio::task::JoinHandle`, keep `fetch_in_progress = true`, and let the normal `poll(100ms) → draw` loop continue. Each draw computes a fresh `spinner_frame()` and a time-derived provider-phase label. A one-shot channel or `JoinHandle::is_finished()` check detects completion; `Esc` aborts the handle.

### File-by-file changes

#### 1. `cli/src/tui/theme.rs`

- Keep `SPINNER_FRAMES` and `spinner_frame()` as-is (time-based, already correct). Verify it is **called inside `render_loading` on every `draw`**, not cached.

```rust
// existing — no behavioral change, but call site must not cache the result
pub fn spinner_frame() -> &'static str { /* (now_ms / 100) % 10 */ }
pub fn render_loading(frame: &mut Frame, area: Rect, theme: &Theme, message: &str) {
    // format!(" {} {} ", spinner_frame(), message) — freshly each call
}
```

- Optional micro-enhancement: accept a second line (elapsed/estimate) and render two-line centered popup so the phase message and the timer are visually separated. If kept single-line, join with ` — `. No new color; continue using `theme.style_warn()` and `theme.style_warn()` border.

#### 2. `cli/src/tui/job_list_screen.rs`

Introduce lightweight progress state without touching the backend. Minimal shape:

```rust
use std::time::Instant;

pub struct FetchProgress {
    pub started_at: Instant,
    pub handle: Option<tokio::task::JoinHandle<Result<(), String>>>, // or AbortHandle
}

pub struct JobListScreen {
    pub fetch_in_progress: bool,            // preserved (existing guard)
    pub fetch_started_at: Option<Instant>,  // new — for elapsed/phase
    pub fetch_handle: Option<tokio::task::JoinHandle<()>>, // new — for abort/poll
    // ... existing fields unchanged
}

impl JobListScreen {
    pub fn start_fetch(&mut self) { /* guard as today, plus fetch_started_at = Some(Instant::now()) */ }
    pub fn cancel_fetch(&mut self) { /* abort handle, clear flag, clear progress */ }

    /// Heuristic provider-phase label derived from elapsed wall time.
    /// No backend truth — purely an UX estimate.
    pub fn fetch_phase_message(&self) -> String {
        let elapsed = self.fetch_started_at.map(|t| t.elapsed().as_secs()).unwrap_or(0);
        // 0–10s → Gupy (1/3), 10–20s → InfoJobs (2/3), 20s+ → LinkedIn (3/3)
        // Estimated total ~30s; show "elapsed / ~30s" alongside.
        let (phase, label) = match elapsed {
            0..=10 => (1, "Scraping Gupy"),
            11..=20 => (2, "Scraping InfoJobs"),
            _ => (3, "Scraping LinkedIn"),
        };
        format!("{label}… ({phase}/3) — {elapsed}s / ~30s  [Esc] Cancel")
    }
}
```

- `start_fetch()` keeps the no-op guard (`if fetch_in_progress { return; }`) and records `fetch_started_at`.
- `run_fetch()` logic is moved into the spawned task (see `app.rs` below); the method may be retained as a private helper that the task calls (`do_fetch_and_reload()`), or be inlined. Either is acceptable as long as it locks `ApiClient`, calls `fetch_jobs().await`, then on success reloads via `fetch_jobs()` list path and sets a toast.
- `draw_main_content` changes from a static string to `render_loading(frame, area, theme, &self.fetch_phase_message())` so the label animates per frame via `spinner_frame()` + elapsed.

Alternatives that satisfy the spec:
- Store only `fetch_started_at: Option<Instant>` and an `AbortHandle` instead of the full `JoinHandle`; poll via a `tokio::sync::oneshot` channel.
- Keep `fetch_in_progress` as the single source of truth for rendering; `fetch_started_at` and `fetch_handle` are private helpers.

#### 3. `cli/src/tui/app.rs` — the critical fix

Current `fetch_pending` block (lines 309–318) blocks:

```rust
// BEFORE — blocks the event loop for 30–90s
let fetch_pending = self.job_list_screen.as_ref().is_some_and(|s| s.fetch_in_progress);
if fetch_pending {
    terminal.draw(|frame| self.render(frame))?;
    if let Some(s) = self.job_list_screen.as_mut() { s.run_fetch().await; }
    continue;
}
```

Change to **spawn + poll**:

```rust
// AFTER — non-blocking, spinner keeps animating via the poll loop below
// A. Spawn-once branch — runs exactly once per fetch press
if self.job_list_screen.as_ref().is_some_and(|s| s.fetch_in_progress)
    && self.job_list_screen.as_ref().is_some_and(|s| s.fetch_handle.is_none())
{
    // take Arcs out before spawn; do not hold &mut across await
    let client = self.api_client.clone();
    let cache = self.cache.clone();
    // clone a channel sender or use a shared completion flag
    let jobs_clone = self.job_list_screen.as_mut().unwrap().fetch_started_at; // or just Instant
    let handle = tokio::spawn(async move {
        let res = client.lock().await.fetch_jobs().await;
        // On Ok, reload list via GET /api/jobs and update cache
        // Return Result<String, String> or send toast via oneshot/mpsc
    });
    if let Some(s) = &mut self.job_list_screen { s.fetch_handle = Some(handle); }
    // do NOT await here — fall through to the normal poll/draw loop
}

// B. Poll branch — runs every 100ms tick while fetch_handle exists
if let Some(screen) = &mut self.job_list_screen
    && let Some(handle) = &mut screen.fetch_handle
{
    if handle.is_finished() {
        let result = handle.await; // now-safe, handle already finished
        // translate result → toast + screen.fetch_jobs().await or error toast
        screen.fetch_handle = None;
        screen.fetch_in_progress = false;
        screen.fetch_started_at = None;
        // one extra draw so the toast is visible immediately
        terminal.draw(|frame| self.render(frame))?;
    }
    // else: fetch still running — let the normal poll loop draw the spinner
}

// C. Cancellation — inside handle_event, when Esc and fetch_in_progress:
// if let Some(s) = &mut self.job_list_screen && s.fetch_in_progress {
//     if key.code == KeyCode::Esc { s.cancel_fetch(); /* aborts handle */ return Ok(()); }
// }
```

Key invariants:

- At most **one** spawn per `start_fetch()` press (`fetch_handle.is_none()` guard + existing `fetch_in_progress` guard).
- No `await` of the `JoinHandle` while the loop is in the inner-fetch branch; `await` only after `is_finished()` so it is instantaneous.
- `terminal.draw()` continues to be driven by the existing `poll(100ms)` loop at the bottom of `run()`; the fetch branch must **not** `continue` past the poll while the task is running. Remove the `continue` that currently skips the poll loop.
- `Esc` handling must run **even while fetching** — place it at the top of `handle_event` before the early returns for search focus, and ensure `poll` still reads events while `fetch_handle` is `Some`.

Sketch of `handle_event` Esc addition (inside the `KeyCode::Esc` arm or a new top check):

```rust
if self.job_list_screen.as_ref().is_some_and(|s| s.fetch_in_progress) {
    if key.code == KeyCode::Esc {
        if let Some(s) = &mut self.job_list_screen { s.cancel_fetch(); }
        return Ok(());
    }
}
```

`ApiClient::fetch_jobs()` already honors the 600 s per-request timeout; aborting the `JoinHandle` drops the in-flight `reqwest` future which cancels the HTTP call. No backend-side abort needed for `v1`.

#### 4. `cli/src/api.rs`

No change required for `v1`. The existing `fetch_jobs()` per-request timeout (600 s) is already correct. If cancellation should propagate an explicit `reqwest::Error` cancellation rather than just dropping the future, no code change is needed — `JoinHandle::abort()` suffices.

#### 5. `cli/src/tui/mod.rs` / `cli/src/tui/app.rs` draw path

- `job_list_screen::draw_main_content` now calls `render_loading` with `screen.fetch_phase_message()` instead of a static literal.
- Hotkeys bar while fetching should render `[Esc] Cancel fetch` instead of the full list. Reuse `draw_hotkeys_bar` branch on `fetch_in_progress`.

### Sequence (v1, frontend estimation)

```
User presses f
  → JobListScreen::start_fetch()               (sets fetch_in_progress + Instant)
  → App::run sees fetch_in_progress && handle.is_none() → tokio::spawn(fetch_task)
  → loop continues: poll(100ms) → draw
        draw → spinner_frame() (new glyph every 100ms)
              → fetch_phase_message(elapsed) → "Scraping Gupy… (1/3) — 3s / ~30s"
        poll → reads Esc → cancel_fetch() → handle.abort() → toast "Fetch cancelled"
        poll → handle.is_finished() → await → toast + reload list
```

### What intentionally does NOT change in v1

- `POST /api/jobs/fetch` stays synchronous; no `fetchId`, no SSE, no new Spring controller.
- Cache invalidation remains "delete all → fetch → repopulate" on the backend; the TUI reload path stays `GET /api/jobs` → `cache.update_cache_on_fetch`.
- No new config flag.

---

## Alternatives Considered

| Option | Description | Pros | Cons | Verdict |
|--------|-------------|------|------|---------|
| **A. Async fetch by fetchId + polling** | `POST /api/jobs/fetch` returns `{ fetchId }` immediately (202 Accepted). Frontend polls `GET /api/jobs/fetch/{id}/status` every 1–2 s for `{ phase, provider, progress, done, error }`. Backend tracks per-fetch state in memory/DB. | Real provider truth; can stream partial job counts; retry-friendly | Requires new Spring controller, `FetchJob` entity or in-memory registry, auth scoping, tests, Flyway migration; larger change | **Recommended for v2** — design now, build after v1 lands |
| **B. Server-Sent Events (SSE)** | `GET /api/jobs/fetch/stream` opens `text/event-stream`; backend emits `event: provider` / `event: progress` as each provider finishes, then `event: done`. Frontend (`reqwest` + `eventsource` or manual `bytes_stream`) renders incrementally. | Real-time, low-latency, natural "Gupy done → InfoJobs done" narrative; single connection | SSE proxy/buffer issues, reconnection logic, backend emitter (`SseEmitter`) and provider instrumentation, harder to test with WireMock; overkill for ~30 s job | **Consider for v2** if polling is deemed too chatty |
| **C. Frontend time-estimated phases (chosen for v1)** | No backend change. Derive `Gupy (0–10s) → InfoJobs (10–20s) → LinkedIn (20s+)` from wall-clock elapsed since `start_fetch`. Show `elapsed / ~30s`. | Zero backend deploy, ships today, fixes the freeze; heuristic is honest if labeled as estimated; easy to upgrade to A/B later (just replace `fetch_phase_message` with polled truth) | Not truthful per-provider timing (a slow LinkedIn can exceed estimate); no partial list updates until fetch completes (unless the optional 5 s `GET /api/jobs` poll is added) | **Chosen for v1** — best cost/benefit; upgrade path documented |
| **D. Blocking spinner only (rejected)** | Keep current `await run_fetch()` but add a second `terminal.draw()` before it. | Trivial one-line change | Spinner still frozen (only one extra frame); input still blocked; does not satisfy FR-1 / NFR-1 | **Rejected** |
| **E. Incremental list refresh without backend status** | While fetch runs, poll `GET /api/jobs` every 5 s and append new rows live. | Users see jobs appearing incrementally even without backend progress API | Poll may hit the DB while the backend is mid-scrape (partial writes); cache invalidation semantics are "delete all" at start, so early polls could show empty or inconsistent list; extra load | **Optional for v1** if cache semantics allow, otherwise defer to v2 where backend can expose a consistent snapshot |

---

## Expected Behavior (Scenarios)

### Scenario 1: spinner animates for the entire fetch duration
- **GIVEN** the JobList screen with `fetch_in_progress == false`
- **WHEN** the user presses `f` and the backend takes 30 s to answer
- **THEN** `render_loading` is drawn every 100 ms tick for the full 30 s
- **AND** `spinner_frame()` cycles through the 10 Braille glyphs at ~10 fps (visible rotation)
- **AND** the `⟳ Fetching…` message remains visible until the fetch completes or is cancelled

### Scenario 2: provider-phase message updates by elapsed time
- **GIVEN** a fetch started at `t=0`
- **WHEN** wall-clock advances
- **THEN** the loading message reads `Scraping Gupy… (1/3) — 3s / ~30s` for `0–10 s`, `Scraping InfoJobs… (2/3) — 12s / ~30s` for `10–20 s`, and `Scraping LinkedIn… (3/3) — 25s / ~30s` thereafter
- **AND** the label is recomputed on every `draw`, not cached at `start_fetch` time

### Scenario 3: re-trigger guard
- **GIVEN** `fetch_in_progress == true` (fetch already running)
- **WHEN** the user presses `f` / `F` again
- **THEN** the key is a no-op (no second `tokio::spawn`, no second HTTP call)

### Scenario 4: cancel with Esc
- **GIVEN** a fetch is in progress
- **WHEN** the user presses `Esc`
- **THEN** the in-flight `POST /api/jobs/fetch` is aborted (`JoinHandle::abort`), `fetch_in_progress` is cleared, the loading popup disappears, and a "Fetch cancelled" toast appears
- **AND** the job list remains as it was before the fetch (no partial reload, no error popup)

### Scenario 5: success path
- **GIVEN** a fetch completes with `200 OK`
- **WHEN** the spawn task finishes
- **THEN** the TUI calls `GET /api/jobs` (same path as `r`), upserts the cache, re-renders the list, and shows a success toast (`Fetch completed` or `Fetch completed — N jobs` with delta)
- **AND** `fetch_in_progress` and the loading popup are cleared

### Scenario 6: failure path
- **GIVEN** a fetch fails (network error, 5xx, or 600 s timeout)
- **WHEN** the spawn task returns `Err`
- **THEN** a red error toast shows `Fetch failed: <reason>`, the previous list stays visible, and `fetch_in_progress` is cleared
- **AND** the user can retry with `f` or `r`

### Scenario 7: non-blocking input while fetching
- **GIVEN** a fetch is in progress
- **WHEN** the user presses `Esc` or `Ctrl+C` within 200 ms
- **THEN** the app processes the key within one `poll(100ms)` tick (no 30 s freeze)
- **AND** `Ctrl+C` still force-quits regardless of fetch state

### Scenario 8: poll loop not spinning
- **GIVEN** a fetch is in progress
- **WHEN** the app is observed with `cargo run` and a CPU monitor
- **THEN** CPU usage stays near idle (draw capped at ~10 fps via `poll(100ms)`), not a hot spin loop

---

## Business Rules

- **BR-1 — No backend contract change in v1.** `POST /api/jobs/fetch` remains synchronous; any async `fetchId`/SSE design is additive and backward-compatible.
- **BR-2 — Healing label is an estimate, not a promise.** Phase labels are wall-clock heuristics and must be worded as such (`Scraping…`, `~30s`), never as backend-reported facts in v1.
- **BR-3 — Single-flight guarantee.** At most one fetch task exists at a time (`fetch_in_progress` + `fetch_handle.is_none()` double guard). Backend is not asked to handle concurrent scrapes from the same user.
- **BR-4 — Cancellation is client-side.** `Esc` aborts the client task; the backend may continue scraping until its own timeout, but the client discards the result and does not reload the list.
- **BR-5 — Success reload reuses the `r` path.** Cache upsert, `from_cache`/`cache_stale` flags, and empty-state handling are identical for `f`-success and `r`-refresh.
- **BR-6 — Theme tokens are reused.** No new colors or widget types for the loading state; `render_loading` remains the single rendering primitive.

---

## Error Cases

| Situation | Behavior |
|-----------|----------|
| Backend returns 5xx during `POST /api/jobs/fetch` | Spawn task returns `Err`; TUI shows `Fetch failed: <body>` error toast; list unchanged; `fetch_in_progress` cleared |
| Backend unreachable (`Network` / `ApiError::HttpError`) | Same as 5xx; toast explains connectivity; `r` remains available for retry |
| 600 s per-request timeout fires | Toast `Fetch failed: request timed out after 600s`; suggest retry; no list change |
| User presses `Esc` mid-fetch | `JoinHandle::abort()`; toast `Fetch cancelled`; no error popup; no list reload |
| User presses `Ctrl+C` mid-fetch | `should_quit = true` + task abort on drop; app exits |
| Backend succeeds but `GET /api/jobs` reload fails | List stays as before fetch; toast `Fetch completed but failed to reload: <reason>` (warn style) — not a silent success |
| Two rapid `f` presses (race) | Second press sees `fetch_in_progress == true` and is ignored; only one `JoinHandle` ever exists |

---

## Out of Scope

- Backend async fetch API (`fetchId` / `GET /status` / SSE). Designed as **v2** and intentionally not built in this spec.
- Partial/incremental list updates mid-scrape beyond the optional 5 s `GET /api/jobs` poll (if that poll is excluded in implementation, it is explicitly v2).
- Per-provider real progress (jobs scraped per provider, e.g. "Gupy: 12 jobs") — requires backend instrumentation, deferred to v2.
- Retry-with-backoff for fetch — existing `AiAnalysisService` backoff is unrelated; fetch retry is manual `f`/`r`.
- Changing the 600 s timeout or the `cli-tui-spec.md` scrape-phase documentation.
- New dependencies or config flags.

---

## v2 Evolution (informative, not required to implement)

When `v1` is shippable, upgrade `fetch_phase_message()` to real data with minimal TUI churn:

**Option A — Polling (recommended for v2):**

```
POST /api/jobs/fetch  →  202 Accepted  { "fetchId": "uuid", "statusUrl": "/api/jobs/fetch/{id}/status" }
GET  /api/jobs/fetch/{id}/status  →  { "state": "RUNNING"|"DONE"|"FAILED", "currentProvider": "LINKEDIN", "providers": { "GUPY": "DONE", "INFOJOBS": "RUNNING", ... }, "jobsFound": 17, "elapsedMs": 12300 }

TUI: start_fetch → spawn polling loop (every 1s) → update fetch_phase_message from JSON truth → on DONE call GET /api/jobs.
```

- Backend: new `FetchJob` in-memory registry (or `fetch_jobs` table if persistence desired), `ProviderRegistry` emits events per provider, controller returns 202, status endpoint is scoped to current user.
- TUI delta: replace `fetch_phase_message()` heuristic with `status.currentProvider`; nothing else in `app.rs` spawn/poll structure changes.

**Option B — SSE:** `GET /api/jobs/fetch/stream` → `text/event-stream` with `event: provider` chunks; frontend uses `reqwest::bytes_stream` + `eventsource` parser. Prefer A unless streaming is proven cheaper.

Either v2 option preserves the v1 spawn/poll/cancel skeleton; only the message source changes from `Instant::elapsed()` to server truth.

---

## Test Scenarios

### Unit Tests (`cli/src/tui/job_list_screen.rs` — inline)

| Test | Setup | Assertion |
|------|-------|-----------|
| `start_fetch_sets_flag_and_timestamp` | `fetch_in_progress == false` → `start_fetch()` | `fetch_in_progress == true` && `fetch_started_at.is_some()` |
| `start_fetch_noop_when_already_in_progress` | `fetch_in_progress == true` → `start_fetch()` | No state change, no second timestamp |
| `cancel_fetch_clears_flag_and_aborts_handle` | Spawn mock handle → `cancel_fetch()` | `fetch_in_progress == false`, `fetch_handle.is_none()`, `handle.is_finished()` (aborted) |
| `fetch_phase_message_at_3s_is_gupy` | `fetch_started_at = now - 3s` | `fetch_phase_message()` contains `Gupy` and `1/3` |
| `fetch_phase_message_at_12s_is_infojobs` | `fetch_started_at = now - 12s` | contains `InfoJobs` and `2/3` |
| `fetch_phase_message_at_25s_is_linkedin` | `fetch_started_at = now - 25s` | contains `LinkedIn` and `3/3` |
| `fetch_phase_message_contains_elapsed_and_estimate` | any elapsed | matches regex `\\d+s / ~30s` |
| `draw_main_content_while_fetching_renders_loading` | `fetch_in_progress == true` | `TestBackend` buffer contains spinner glyph and `Scraping` |
| `filter_cycle_keybinding_cycles_states` | (existing) | unmodified |

### Theme Tests (`cli/src/tui/theme_test.rs` or inline in `theme.rs`)

| Test | Assertion |
|------|-----------|
| `spinner_frame_cycles_within_10_frames` | 20 successive calls within 1 s cover ≥ 3 distinct glyphs (no stuck frame) |
| `render_loading_does_not_panic_with_fetch_message` | `render_loading(..., fetch_phase_message)` on `TestBackend(80,24)` does not panic |
| `render_loading_centered_popup_with_estimate` | Popup width/height constants still 50×8 or two-line variant renders |

### TUI Integration (`cli/src/tui/app_integration_test.rs`)

Mock `ApiClient` via `httpmock`/`wiremock` with a delayed `POST /api/jobs/fetch` (e.g. 2 s delay) so the spinner can be observed mid-flight.

| Test | Scenario |
|------|----------|
| `fetch_spinner_animates_during_slow_fetch` | Mock fetch with 1.5 s delay → spawn → assert `draw` called ≥ 5 times while `fetch_handle.is_some()` and buffer changes (spinner glyph rotates) |
| `fetch_success_reloads_list_and_shows_toast` | Mock `POST /fetch → 200` + `GET /jobs → [job]` → trigger `f` → wait for handle → assert list len == 1 and toast `Fetch completed` |
| `fetch_failure_shows_error_toast` | Mock `POST /fetch → 500` → trigger `f` → assert error toast and list unchanged |
| `fetch_cancel_with_esc_aborts_and_clears` | Mock slow fetch (5 s) → `f` → after 200 ms send `Esc` key event → assert `fetch_in_progress == false`, handle aborted, toast `Fetch cancelled`, no `GET /jobs` call |
| `fetch_guard_prevents_second_spawn` | Trigger `f` twice within 100 ms → assert only one `POST /fetch` mock hit |
| `fetch_does_not_block_ctrl_c` | Slow fetch (5 s) → `f` → after 100 ms send `Ctrl+C` → assert `should_quit == true` within 200 ms |

### Batch / API Mock (`cli/tests/integration_tests.rs`)

| Test | Coverage |
|------|----------|
| `fetch_command_still_uses_600s_timeout` | Assert `ApiClient::fetch_jobs` per-request timeout is 600 s (existing assertion preserved) |

### Manual QA Checklist

- [ ] Press `f` on a populated list — spinner rotates continuously, message cycles Gupy → InfoJobs → LinkedIn over ~30 s, `[Esc] Cancel` visible.
- [ ] Press `Esc` at 5 s — fetch aborts within 100 ms, toast "Fetch cancelled", list unchanged, subsequent `f` works.
- [ ] Let fetch succeed (backend running) — toast "Fetch completed", list refreshes, scroll position sane.
- [ ] Disconnect backend, press `f` — error toast within timeout, no freeze, `r` still works.
- [ ] CPU check: `top`/`htop` shows no spin while fetching; terminal redraw ~10 fps.
- [ ] Resize terminal while fetching — no panic, popup stays centered.

---

## Agent Prompt (OpenCode)

```
Read the spec at docs/specs/cli-fetch-ux.md.

This is a frontend-only (v1) TUI fix for the frozen fetch UX. No Spring
backend changes in this phase; POST /api/jobs/fetch stays synchronous.

Step 1 — write the failing tests (RED) for:
  - JobListScreen::start_fetch / cancel_fetch / fetch_phase_message
  - theme::spinner_frame cycling + render_loading with the new provider-phase message
  - App::run non-blocking fetch: spawn + poll(100ms) + Esc cancel + single-flight guard
    (use a delayed httpmock for POST /api/jobs/fetch so the spinner is observable)
Do not change production code yet.

Step 2 — wait for confirmation before implementing.

Step 3 — implement (GREEN) per "Proposed Implementation":
  - cli/src/tui/theme.rs — keep spinner_frame() time-based; render_loading consumes a fresh frame each draw.
  - cli/src/tui/job_list_screen.rs — add fetch_started_at + fetch_handle + FetchProgress helper + fetch_phase_message() heuristic (Gupy 0–10s → InfoJobs 10–20s → LinkedIn 20s+, elapsed/~30s).
  - cli/src/tui/app.rs — replace the blocking fetch_pending branch (draw once → await run_fetch) with spawn-once + is_finished poll + Esc abort. Remove the continue that skips the poll loop while fetching. Handle Esc even while fetch_in_progress.
  - Preserve: f/F no-op guard, 600s per-request timeout, 100ms poll cadence, Theme::style_warn tokens.
  Verify every scenario in "Expected Behavior" and "Test Scenarios".

Step 4 — suggest the v2 backend evolution (fetchId polling or SSE) only after v1 is green.
```
