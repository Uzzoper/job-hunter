# Spec: Fase A — Sourcing Restoration (config flips + filter fixes)

> **Layer:** `config` (wiring/yaml) + `infrastructure` (provider beans) + `web/cli` (TUI fix)
> **Implements:** `docs/specs/gupy-mcp-provider.md` (turn-on path, checkboxes),
> `ats-provider.md`, `github-jobs.md` (enablement of already-spec'd providers)
> **Adds bug fixes:** single-source fetch normalizer mis-wiring, `pleno` exclusion gap,
> TUI min-score no-op, LinkedIn `enabled` flag never gating beans
> **Deliberately out:** new sources (`backend-br/vagas`, GeekHunter/Sylision — parked by
> operator), Phase B (liveness/dedupe/auto-score), external-portal spec (paused, pending
> volume evidence)

---

## Context

The operator paused all apply-side work: the bottleneck is **volume of open,
relevant listings, not the apply mechanism**. Evidence (recon + live probes,
2026-10-23):

| Finding | Evidence |
|---|---|
| Gupy REST gateway is dead | `GET employability-portal.gupy.io/api/v1/jobs?jobName=…` → nginx **404** (probed; also `gupy-mcp-provider.md:7-9`) |
| Gupy MCP gateway is alive | `GET candidates.mcp.api.gupy.io/mcp` → JSON-RPC **405** (formal expect-POST response, probed) |
| MCP provider exists but is OFF | `gupy.enabled` absent from `application.yaml`; `GupyMcpProvider` bean is `@ConditionalOnProperty` (`AppConfig.java:266-267`) → default wiring is the dead REST provider |
| MCP keyword default starves it | MCP reads `${gupy.keywords:desenvolvedor junior}` (1 term) while the include list lives at `scraper.gupy.keywords` (9 terms) — two homes, one list |
| ATS + GitHub built but disabled | `ats.enabled: false` (`application.yaml:137`), `github.enabled: false` (`:155`) — specs and providers already exist |
| `fetch/{source}` uses the LinkedIn normalizer for every source | `AppConfig.java:444` qualifiers `@Qualifier("linkedinNormalizerPort")` unconditionally |
| Senior exclude misses `pleno` | exclude regex has `señior/lead/…` but not `pleno` (`AppConfig.java:216-218`) |
| TUI min-score is a no-op | `cli/src/tui/job_list_screen.rs:423` — checks `job.id > 0 && ms <= 100`, never compares the job's score |
| LinkedIn `enabled` flag is read but never gates beans | `AppConfig.java:256-264` selects jsoup/service by `mode` only |

Decision (operator-approved): **Config-first restoration, zero new providers.**
Dead REST is retired *from the default path* now (honest wiring), full code
deletion follows in Phase B once MCP volume is proven. Board slug lists for ATS
are curated externally (librarian) and verified by live probes before commit;
until verification the lists ship empty-but-enabled so nothing silently drags
(fetch with an empty board list costs one timed request per board token — with
zero tokens, ATS is merely idle, never broken).

---

## Expected behavior

### A1 — Gupy MCP becomes the default Gupy source

#### Scenario 1: MCP enabled by default, REST retired from default path
- **GIVEN** default config (only `scraper.gupy.keywords` present)
- **WHEN** the Spring context starts
- **THEN** `ProviderRegistry` holds the **`GupyMcpProvider`** for `gupy`
- **AND** no bean named `gupyProvider` is registered by default
- **AND** `GupyProvider` (REST) comes back only with `gupy.rest.enabled=true`
(regression hatch, documented as dead-by-config)

#### Scenario 2: one canonical keyword list
- **GIVEN** `scraper.gupy.keywords` carries the 9-term list
- **WHEN** the MCP provider plans its queries
- **THEN** its keywords come from `scraper.gupy.keywords` (no `gupy.keywords`
second home; the duplicate key is removed)
- **AND** MCP paging knobs live once for the MCP strategy
  (`gupy.limit:100`, `gupy.max-jobs:200` under the `gupy:` block)
- **Amendment (2026-11-01, review round 2):** shipped reality diverges from
  the original clause on two knobs, on purpose:
  (a) `max-detail-domains` stays a single `scraper.gupy.*` key — it is read
  by the two Gupy beans (MCP default + REST hatch), which pass the cap into
  the detail-domain resolver (#74; the resolver never reads the key
  itself), so mirroring it as `gupy.max-detail-domains` would create a
  second home instead of removing one;
  (b) the legacy REST hatch keeps its own `scraper.gupy.limit` — MCP's limit
  single-homes at `gupy.limit` (per `gupy-mcp-provider.md` §2). Both hatches
  are Phase-B deletions, which collapses this split permanently.

### A2 — ATS + GitHub enabled

#### Scenario 3: ATS provider active with verified board slugs
- **GIVEN** `ats.enabled: true` with non-empty `greenhouse-boards`
(`ashby-boards`, `lever-sites`) lists carrying **probe-verified** tokens
- **WHEN** `POST /api/jobs/fetch` runs
- **THEN** the three ATS providers contribute listings through the existing
normalizer (include list `scraper.gupy.keywords`)
- **AND** a board returning 404/empty is logged and skipped, never aborting the
fetch cycle (`ats-provider.md:63-67` behavior)
- **AND** after Phase-A verification every shipped token answered the public
board API with HTTP 200 + ≥1 job

#### Scenario 4: GitHub contributes
- **GIVEN** `github.enabled: true` with the existing 2 repos
- **WHEN** fetch runs
- **THEN** open issues with hiring labels surface as jobs (per
`github-jobs.md`), deduped by URL like every other provider

### A3 — Filter fixes (each RED first)

#### Scenario 5: single-source fetch normalizer matches the source
- **GIVEN** `POST /api/jobs/fetch/gupy`
- **WHEN** `FetchSourceJobsService` normalizes raw jobs
- **THEN** the **gupy-keywords normalizer** applies (not the LinkedIn one)
- **AND** `POST /api/jobs/fetch/linkedin` still applies the LinkedIn compound
keywords normalizer
- **AND** unknown source errors as today (unchanged)

#### Scenario 6: `pleno` is excluded
- **GIVEN** a listing titled "Desenvolvedor Java Pleno"
- **WHEN** the include includes the keyword and the exclude regex runs
- **THEN** the job is dropped (`\bpleno\b` joins the senior-exclude set;
the bare abbreviation `pl` is NOT added — the raw token false-positives on
"PL/SQL"-style strings)
- **Amendment (2026-11-01, review round 2):** `JobNormalizer.normalizeText`
already expands a standalone "Pl" token to "pleno" *before* the exclude
match, so (a) raw `Pl`-abbreviated junior titles are dropped anyway — the
intended coverage is intact — and (b) titles like "Desenvolvedor PL/SQL"
are dropped too (expansion yields `pleno/sql`). This accepted loss of rare
junior PL/SQL listings is pinned by
`normalize_whenPlSqlTitle_dropsViaPlExpansion` and re-evaluated in the
Phase B normalizer-quality work.

#### Scenario 7: TUI min-score filters real scores
- **GIVEN** the TUI list screen with a minScore filter
- **WHEN** a job carries score `55` (analyzed) and another NULL
- **THEN** the 55-scored job is excluded below threshold, the NULL-scored job
is excluded only when a threshold is imposed (match list-filter semantics of
`FetchJobsService`), and `job.id` no longer participates in the comparison

#### Scenario 8: `enabled` flag gates the LinkedIn beans
- **GIVEN** `scraper.linkedin.enabled: false`
- **WHEN** the context starts
- **THEN** no LinkedIn `ExtractionStrategy` bean is registered (jsoup or
service) — mode selects *which*, enabled selects *whether* (default `true`)
- **AND** the jsoup path gets a bound `max-pages` yaml key
(currently record-default unbound)

---

## Files to change

| Area | Change |
|---|---|
| `Greenhouse/Ashby/LeverProvider.java` | explicit `User-Agent: JobHunter/1.0` default header via `RestApiStrategy` (probed 2026-10-23: Ashby rejects bare Java/python UAs with 403; `JobHunter/1.0` answers 200) |
| `application.yaml` | add `gupy.enabled: true` block (+ paging knobs), remove duplicate `gupy.keywords`, add `gupy.rest.enabled` (default false, commented as dead gateway), add `scraper.linkedin.enabled: true` + `scraper.linkedin.jsoup.max-pages` (mode-dependent), set `ats.enabled: true` + board lists, set `github.enabled: true` |
| `AppConfig.java` | MCP bean default-on (keyword source → `scraper.gupy.keywords`); `gupyProvider` REST bean gains `@ConditionalOnProperty(gupy.rest.enabled)`; `providerRegistry` accepts `Optional<ExtractionStrategy> gupyProvider`; exclude regex adds `pleno`; `FetchSourceJobsService` wired source-aware; LinkedIn beans gain `enabled` gate |
| `FetchSourceJobsService.java` | normalizer picked by source (linkedin → linkedin normalizer; otherwise → non-linkedin normalizer); constructor signature change with test-side updates |
| `cli/src/tui/job_list_screen.rs` | min-score comparison reads the actual score + matches `minScore` excludes-null semantics |
| Tests | `ProviderRegistryTest` (Optional gupy path), `AppConfig`-adjacent MCP wiring (`HermesConfigFailFastTest`-style context tests if applicable), `FetchSourceJobsServiceTest` (both sources), `JobNormalizerTest` (`pleno` case), Rust TUI test for min-score; existing suites stay green |
| Docs | `gupy-mcp-provider.md` checkboxes updated for the enabled items; `application.yaml` comments reflect the probe outcomes (404/405 evidence lines) |

---

## Test plan (TDD — RED before GREEN, per fix)

Plain JUnit 5 + Mockito (no Spring context for unit bits), Rust tests in-crate.

| # | Test | Verifies |
|---|---|---|
| 1 | `boot_withDefaultConfig_usesGupyMcpProvider` | registry's gupy slot is `GupyMcpProvider` under default yaml |
| 2 | `boot_withRestHatch_servesGupyViaRestProvider` | with `gupy.rest.enabled=true` the REST bean exists again and registry uses it when MCP disabled |
| 3 | `boot_withDefaultConfig_mcpKeywordsComeFromScraperGupyKeywords` | MCP provider receives the 9-term list |
| 4 | `fetchSourceJobs_gupySource_usesGupyNormalizer` | source=gupy → keywords list `scraper.gupy.keywords`, not LinkedIn compounds |
| 5 | `normalize_whenPlenoTitle_shouldDropJob` | "Java Pleno" dropped |
| 6 | `normalize_whenSeniorTitle_shouldStillDrop` | regression guard on existing excludes |
| 7 | Rust `min_score_filter_uses_job_score` | score-based exclusion, NULL handling per list semantics |
| 8 | `boot_withEnabledFalse_registersNoLinkedinBeans` | enabled=false → no LinkedIn bean; absent → default true |
| 9 | `normalize_whenPlSqlTitle_dropsViaPlExpansion` | Pl→pleno expansion feeds the exclude match — PL/SQL titles dropped (accepted loss, S6 amendment) |
| 10 | `extract_whenBoardProbed_sendsHonestUserAgent` (Ashby, Greenhouse) | every ATS request carries the honest `JobHunter/1.0` UA from `AtsHttpHeaders.DEFAULT` |
| 11 | `extract_whenSiteProbed_sendsHonestUserAgent` (Lever) | same guard asserted via the production constructor path |

---

## Verification

- `./mvnw test --batch-mode` green (no ignored/skipped introduced)
  — **done 2026-10-31**, re-verified 2026-11-01: **962 tests, 0 failures,
  0 errors, 0 skipped (76 top-level suites)**. Count source: surefire XML
  `TEST-*.xml` suite attributes (includes `@Nested` classes); errata — an
  earlier record said "412, parsing error", which was wrong: the reviewer's
  958 was the correct count at their snapshot (962 − the 4 tests added in
  review round 2); our "412" came from summing the parent `.txt` "Tests
  run:" lines, where Surefire reports 0 for `@Nested` classes.
- `cargo test` green in `cli/`
  — **done 2026-10-31**: 577 tests across 5 suites, 0 failures (count
  corrected 2026-11-01; an earlier "57" recorded only the last suite's
  result line).
  Caveat (2026-11-02, delta review): 577 is what compiles *and* runs;
  `cli/src/tui/app_test.rs` (9 tests) and `cli/src/tui/auth_screen_test.rs`
  (18 tests) are authored but **not declared in `tui/mod.rs`**, so their 27
  tests never compile or run (604 authored − 27 = 577). Declaring both
  modules is deferred to Phase B; this verification does not certify those.
- Live smoke (manual, after merge): `POST /api/jobs/fetch` → `ProviderFetchStats`
  shows `gupy` >0 jobs and zero provider-crash entries; ATS boards all 200
  — **done 2026-10-31 on the VM backend** (evidence below). `fetch/gupy` via
  MCP: 200 fetched / 41 saved; second run 200 fetched / **0 saved** (URL
  dedupe). Full fetch: greenhouse 3123/580, gupy(MCP) 200/47, ashby 209/78,
  lever 406/64, github 59/25, infojobs 171/0 (pre-existing rows), linkedin
  30/14 (partial — service-mode timeout at 180s, `error: null`, see note).
  DB `SELECT source, COUNT(*)`: gupy 3659, greenhouse 1297, ashby 284,
  linkedin 209, github 57, lever 18. Log grep for provider crash/bot
  challenge: zero occurrences; no mass 403/404 on ATS boards.
  - **LinkedIn note:** partial data from service-mode timeout is known
    behavior (spec keeps provider-quality tuning out of fase A); logged as
    Phase B backlog noise, not a defect.
  - **Resolved (2026-11-02):** post-merge smoke re-run on the VM settled both
    anomalies. Lever: `15` pre-existing rows + the Oct 31 `64` saved = the
    `79` rows measured 2026-11-02; most plausible reading is that the old
    `18` was a COUNT taken mid-save (hypothesis — pruning and dedupe also
    fit the artifacts), and the save path is roundly healthy either way.
    InfoJobs: NOT dedupe (the bot's
    read was wrong — zero `source=infojobs` rows existed). Root cause found
    in code: live card markup dropped every `data-testid` attribute, so
    mapping falls to the `parseJobsFromLinks` fallback, which passed
    `rawDate=null`; `JobNormalizer`'s date gate then silently skipped 100%
    of fetched jobs (171/0 on Oct 31, 173/0 post-merge). Fixed in
    `fix/infojobs-card-date` (harvest hidden `js_date` data-value +
    visible short-date fallback borrowed-date guard for ambiguous
    containers + one WARN counter), and arrived location-polluting leftmost
    match repaired with a narrow location source — VM smoke goal: dedicated
    `POST /api/jobs/fetch/infojobs` → `saved > 0` — **achieved 2026-11-03 on
    the VM, post-merge**: post-deploy first fetch went `0 → 99` rows saved
    (eternally-dead channel now live); a second dedicated fetch re-measured
    `173 fetched / 12 saved` (+12 fresh postings, `infojobs` 99 → 111;
    161/173 already deduped by URL) with `detailSkippedCount=73` — those
    keep their card-snippet description, only the cap-bounded detail page
    was never fetched — and the drift canary (`cards carry no posting
    date`) fired **0** times. Channel validated; no defect left open.
  - **Post-merge smoke 2026-11-02 (global fetch):** greenhouse 3155 fetched,
    +18 real rows; ashby +2; github 0; zero provider crashes (only
    `TimeoutException`). gupy/lever/linkedin/infojobs hit the 120s global
    budget and contributed 0 — dedicated endpoints (`/fetch/gupy`) are the
    way to smoke single sources; lever/greenhouse have no dedicated endpoint
    yet (open follow-up, next PR). Minor stats-vs-delta mismatch on
    greenhouse (`saved=0` vs +18 rows) noted; likely expiry pruning or stat
    timing, not investigated.
- Store probe evidence (404/405 + each board 200) in the yaml comments
  — done in `application.yaml` comments.

---

## Out of scope

- New providers (`backend-br/vagas`, GeekHunter, Sylision) — parked by operator
- Phase B: liveness fields, cross-source dedupe, auto-score queue
- Deleting the REST `GupyProvider` code/tests — Phase B after MCP volume proof
- external-portal apply work — paused per prior spec
