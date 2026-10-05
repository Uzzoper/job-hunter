# Gupy MCP Provider (replaces dead JSON search API) — Spec

Issue: TBD (Gupy aggregator revival, after #74) · Status: approved (implementing)

## 1. Context

The public Gupy search JSON API is dead on every probed host
(`employability-portal.gupy.io`: 404 incl. root; `portal.gupy.io/api/v1/jobs`:
200 but SPA HTML; per-company `/api/v1/jobs`: 404). Gupy's official candidate
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
  keywords: [desenvolvedor junior, dev junior, programador junior, ...]  # CSV, junior-first compounds (LinkedIn lesson)
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
  (dead query skipped, never fails the fetch); honor 429/`Retry-After`.
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

- [ ] `search_jobs` returns title/company/url/description/postedAt/location
      through the `RawJob` contract; old host/paths removed (no dead code).
- [ ] WireMock suites green (valid / paged / empty / 500 / 429); full
      `./mvnw test` green; no Spring in new unit tests.
- [ ] Parity sample: same keyword set via MCP vs 100 stored pre-death Gupy
      rows — field presence compared (title 100%, company 100%, url 100%,
      description substantively present, dates parseable).
- [ ] Junior-relevance validated on a sample fetch before enabling by default
      (`gupy.enabled` gate pattern like `ats.enabled`, default `false` until
      sample sign-off, then `true`).
- [ ] `AppConfig` is the only `@Value` layer; records, no Lombok,
      conventional commits, SDD+TDD pair commits.

## 9. Wiring & rollout

- `gupy.enabled` (default `false`): when `true`, `AppConfig` registers
  `GupyMcpProvider` under providerId `"gupy-mcp"` via `@ConditionalOnProperty`;
  the existing `GupyProvider` (REST) remains registered under `"gupy"` when
  wired, so both may coexist during parity. Do **not** register both for the
  same providerId — fetch-all must not duplicate sources.
- Config keys: `gupy.mcp-url`, `gupy.timeout-seconds`, `gupy.keywords`,
  `gupy.limit`, `gupy.max-jobs`, `gupy.enabled` (all `@Value` only in AppConfig).
- Precondition for flipping `gupy.enabled=true` (default `false`): run a
  parity sample (keyword set via MCP vs 100 stored pre-death Gupy rows) and
  sign off on field presence/date/description/workModel (Acceptance §7). Keep
  the REST provider the default until sample passes.


## 8. Out of scope

`list_companies`/`get_company_by_id` browsing (future enrichment),
`disabilities` gate wiring (noted, separate), apply flows (bot skill
territory), cutoff/prompt/scorer changes.
