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
- **AND** MCP paging knobs (`gupy.limit:100`, `gupy.max-jobs:200`,
`gupy.max-detail-domains:100`) live once under the existing Qualifier block

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
the bare abbreviation `pl` is NOT added — false-positive on "PL/SQL"-style
tokens is documented as the reason)

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
| 1 | `defaultWiring_usesGupyMcpProvider` | registry's gupy slot is `GupyMcpProvider` under default yaml |
| 2 | `restEnabledProperty_resurrectsGupyProvider` | with `gupy.rest.enabled=true` the REST bean exists again and registry uses it when MCP disabled |
| 3 | `mcpKeywords_comeFromScraperGupyKeywords` | MCP provider receives the 9-term list |
| 4 | `fetchSourceJobs_gupySource_usesGupyNormalizer` | source=gupy → keywords list `scraper.gupy.keywords`, not LinkedIn compounds |
| 5 | `normalize_whenPlenoTitle_shouldDropJob` | "Java Pleno" dropped |
| 6 | `normalize_whenSeniorStillDropped` | regression guard on existing excludes |
| 7 | Rust `min_score_filter_uses_job_score` | score-based exclusion, NULL handling per list semantics |
| 8 | `linkedinBeans_gatedByEnabledFlag` | enabled=false → no LinkedIn bean; absent → default true |

---

## Verification

- `./mvnw test --batch-mode` green (no ignored/skipped introduced)
- `cargo test` green in `cli/`
- Live smoke (manual, after merge): `POST /api/jobs/fetch` → `ProviderFetchStats`
  shows `gupy` >0 jobs and zero provider-crash entries; ATS boards all 200
- Store probe evidence (404/405 + each board 200) in the yaml comments

---

## Out of scope

- New providers (`backend-br/vagas`, GeekHunter, Sylision) — parked by operator
- Phase B: liveness fields, cross-source dedupe, auto-score queue
- Deleting the REST `GupyProvider` code/tests — Phase B after MCP volume proof
- external-portal apply work — paused per prior spec
