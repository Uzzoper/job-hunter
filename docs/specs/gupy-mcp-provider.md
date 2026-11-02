# Gupy MCP Provider (replaces dead JSON search API) — Spec

Issue: TBD (Gupy aggregator revival, after #74) · Status: approved (implementing)

## 1. Context

The public Gupy search JSON API is dead on every probed host
(`employability-portal.gupy.io`: 404 incl. root; `portal.gupy.io/api/v1/jobs`:
200 but SPA HTML; per-company `/api/v1/jobs`: 404). Re-probed 2026-10-23
(fase-a-sourcing-restoration recon): `GET
employability-portal.gupy.io/api/v1/jobs?jobName=…` → nginx **404** while
`GET candidates.mcp.api.gupy.io/mcp` → JSON-RPC **405** (formal expect-POST
response — the MCP gateway is alive; full probe evidence stored in
`application.yaml` comments). Gupy's official candidate
MCP server (`https://candidates.mcp.api.gupy.io/mcp`, `gupy-portal-mcp
v1.0.0`, no auth) exposes the same universe with filters: proven live
2026-10-04 (`desenvolvedor` = 509 jobs; +`isRemote` = 265; `junior` = 1019).

## 2. Design

New `GupyMcpProvider` (`providerId` stays `"gupy"` — downstream
indistinguishable) speaking MCP Streamable HTTP (plain JSON-RPC POSTs via the
existing `RestClient`, no new dependencies), registered in `ProviderRegistry`
**replacing** the `GupyProvider` REST wiring. Old host/paths removed once
parity is green; dead code goes, no parallel paths. `JobNormalizer`
unchanged (keywords, max-age, junior filters, URL dedupe, `EmailExtractor`).

```yaml
gupy:
  mcp-url: https://candidates.mcp.api.gupy.io/mcp   # single endpoint, no auth
  timeout-seconds: 30
  # NOTE: keyword list moved — see §9: MCP reads scraper.gupy.keywords (single
  # canonical list, the old gupy.keywords second home is removed).
  limit: 100        # per-query limit param
  max-jobs: 200     # merged cap across queries (junior-first ordering mitigates head-overlap)
```

## 3. Requests (MCP Streamable HTTP)

- `POST {mcp-url}` `Accept: application/json, text/event-stream`,
  `{"jsonrpc":"2.0","id":N,"method":"tools/call","params":{"name":"search_jobs","arguments":{...}}}`
  with `term`, `limit`, `offset`, `isRemote`, `city/state/country`.
- `initialize` handshake once per fetch (protocolVersion negotiate; no auth).
- Pagination via `offset` while full pages; per-query loop like the ATS
  multi-board pattern; URL-keyed dedupe across queries; per-query try/catch
  (dead query skipped, never fails the fetch); 429 is retried-then-skipped
  with exponential backoff + jitter (`Retry-After` header is not read).
- `get_job_by_id` only as fallback when a search item lacks description
  (search already inlines it — verified).

## 4. Field mapping → `RawJob` (verified live 2026-10-04)

| RawJob | MCP source | Notes |
|---|---|---|
| title | `name` | direct |
| company | `careerPageName` | suffix-strip like before (`"X - Linkedin"`) |
| url | `jobUrl` | direct, non-blank else skip (board URL — feeds existing #74 domain machinery unchanged) |
| description | `description` | FULL inline (better than old API) |
| rawDate | `publishedDate` | ISO → `yyyy-MM-dd` |
| location | `city` + `state` (+ `country` when non-BR) | same composition as before |
| workModel | `workplaceType` | explicit remote/hybrid/onsite (replaces `isRemoteWork` boolean) |
| source | `"gupy"` | hardcoded, unchanged |
| metadata | `companyId`, `disabilities`, `salary`, `type` | `disabilities` reserved for the PcD eliminatory gate (future, not this spec) |

## 5. Error policy (per query, Gupy parity)

- Transport/HTTP errors → `log.error`, skip query, continue (ATS parity).
- `isRemote`/filters are server-side; unconfigured filter = parameter absent.
- Empty result = valid empty list, never null.

## 6. Tests (TDD, WireMock, no Spring context in unit tests)

MCP envelope fixtures (`{"result":{"content":[{"text":"{...jobs...}"}]}}`):
valid search (2+ jobs incl. junior), paged offset loop, empty, HTTP 500 →
skip, 429 → retry-then-skip, blank title/url skip, workplaceType matrix,
company suffix strip. Naming `methodName_scenario_expectedResult` +
`@DisplayName`.

## 7. Acceptance criteria

- [x] `search_jobs` returns title/company/url/description/postedAt/location
      through the `RawJob` contract. ~~old host/paths removed (no dead code)~~ —
      amended by fase-a-sourcing-restoration: the REST `GupyProvider` code stays
      as a documented regression hatch (`gupy.rest.enabled=true` only, default
      off) until Phase B deletes it after MCP volume proof.
- [x] WireMock suites green (valid / paged / empty / 500 / 429); full
      `./mvnw test` green; no Spring in new unit tests. (Fase-A added
      Spring-context *wiring integration* tests only — they live in
      `src/test/java/.../integration/` per the house `AiPortWiringTest` pattern;
      new provider-level unit tests remain context-free.)
- [ ] Parity sample: same keyword set via MCP vs 100 stored pre-death Gupy
      rows — field presence compared (title 100%, company 100%, url 100%,
      description substantively present, dates parseable).
- [x] ~~Junior-relevance validated on a sample fetch before enabling by default~~ —
      superseded by fase-a-sourcing-restoration (operator-approved
      config-first decision): `gupy.enabled` flips to default `true` BEFORE the
      parity sample (REST is dead-by-config, so MCP is the only live source);
      MCP volume proof remains the open gate for Phase B code deletion.
      `gupy.keywords` no longer exists — the keyword source is the canonical
      `scraper.gupy.keywords` (9 terms), wired default-on
      (`boot_withDefaultConfig_usesGupyMcpProvider`/
      `boot_withDefaultConfig_mcpKeywordsComeFromScraperGupyKeywords`).
- [x] `AppConfig` is the only `@Value` layer; records, no Lombok,
      conventional commits, SDD+TDD pair commits.

## 9. Wiring & rollout

- `gupy.enabled` (default `true` as of fase-a-sourcing-restoration): when
  `true`, `AppConfig` registers `GupyMcpProvider` under providerId `"gupy"` via
  `@ConditionalOnProperty` (`matchIfMissing = true`) and it is the sole live
  source for `"gupy"`. The legacy REST `GupyProvider` is retired from the
  default path — it only returns with `gupy.rest.enabled=true`
  (dead-by-config regression hatch). The registry prefers MCP whenever both
  are present, so the two can never double-register the `"gupy"` id.
- Config keys: `gupy.mcp-url`, `gupy.timeout-seconds`, `gupy.limit`,
  `gupy.max-jobs`, `gupy.enabled` (`/gupy.rest.enabled` hatch) — all `@Value`
  only in AppConfig. Keywords are NOT owned here anymore: the MCP provider
  reads `scraper.gupy.keywords` (9 terms — single canonical list shared with
  the normalizers and the REST hatch; verified by
  `boot_withDefaultConfig_mcpKeywordsComeFromScraperGupyKeywords`).
  `scraper.gupy.max-detail-domains`
  caps the #74 detail-domain resolution in the same MCP bean.
- ~~Precondition for flipping `gupy.enabled=true` (default `false`)~~ —
  superseded: fase-a-sourcing-restoration flipped the default before the parity
  sample (REST probed 404, MCP probed 405 — see §1). The MCP volume proof
  (parity sample + providerFetchStats smoke) remains the gate for Phase B.


## 8. Out of scope

`list_companies`/`get_company_by_id` browsing (future enrichment),
`disabilities` gate wiring (noted, separate), apply flows (bot skill
territory), cutoff/prompt/scorer changes.
