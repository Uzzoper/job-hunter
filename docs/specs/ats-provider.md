# ATS Job Source (Greenhouse / Ashby / Lever) — Spec

Issue: #76 · Status: proposed (awaiting approval — no code without it)

## 1. Context

`ProviderRegistry` currently orchestrates Gupy + InfoJobs + LinkedIn.
Greenhouse, Ashby and Lever publish open, documented, no-auth JSON board APIs
built for third-party job-site consumption (same category as the Gupy source:
ATS with a public API). Live research (2026-09-22/23, every endpoint fetched
with 200 OK) confirmed **27 boards** in two tiers: 10 BR employers + 17
remote-first globals whose remote pipeline is BR-eligible (single fetch ≈
2 700 raw jobs). Major BR employers outside these three ATSes (iFood —
SmartRecruiters probe returned empty; Mercado Livre, PicPay, Wildlife,
Creditas, Hotmart — likely Workday/Taleo/proprietary) are **out of scope**.

No MCP runtime dependency in Java — plain HTTP via the existing
`RestApiStrategy` + `RestClient`, mirroring `GupyProvider`.

## 2. Board list (verified live 2026-09-23, frozen at spec time)

Counts are live `jobs` array sizes; rem = remote-flagged, br = Brazil/LatAm
location, jr = junior/intern/estágio title signals (title-only scan, so junior
counts are a floor — the normalizer's junior filter decides downstream).

### Tier 1 — BR employers (10 boards, ≈ 1 060 jobs)

| ATS | Board token / site | Jobs | rem | br | jr | Signal observed |
|---|---|---|---|---|---|---|
| greenhouse | `stone` | 403 | 18 | 385 | 5 | BR-heavy, Júnior titles |
| greenhouse | `xpinc` | 170 | 0 | 142 | 15 | XP São Paulo, 15 Júnior |
| greenhouse | `c6bank` | 150 | 0 | 149 | 12 | C6 São Paulo, 12 Júnior |
| greenhouse | `gympass` | 90 | 37 | 44 | 2 | Wellhub, Brazil (Remote), Jovem Aprendiz bank |
| greenhouse | `quintoandar` | 87 | 0 | 73 | 3 | Brasil locations |
| greenhouse | `ebanx` | 35 | 3 | 24 | 2 | Curitiba + remote |
| greenhouse | `thoughtworks` | 34 | 0 | 0 | 2 | global consultancy, BR offices |
| greenhouse | `vtex` | 29 | 0 | 19 | 0 | Brazil locations |
| greenhouse | `feedzai` | 28 | 3 | 1 | 2 | Brazil Remote engineering |
| ashby | `nubank` | 116 | 99 | 46 | 1 | 46 São Paulo/Campinas/BH/RJ |
| lever | `dlocal` | 54 | 1 | 25 | 4 | Sao Paulo (Hybrid) |

### Tier 2 — remote-first globals, BR-eligible remote pipeline (17 boards, ≈ 1 660 jobs)

| ATS | Board token / site | Jobs | rem | Notes |
|---|---|---|---|---|
| greenhouse | `elastic` | 373 | 0 | 4 Brazil roles, 9 junior |
| greenhouse | `gitlab` | 202 | 179 | all-remote, hires Brazil |
| greenhouse | `remotecom` | 176 | 158 | Remote.com, global incl. Brazil |
| greenhouse | `grafanalabs` | 138 | 138 | all-remote |
| greenhouse | `mozilla` | 82 | 82 | all-remote |
| greenhouse | `monzo` | 72 | 44 | remote roles |
| greenhouse | `tailscale` | 57 | 53 | remote-first |
| greenhouse | `wikimedia` | 12 | 12 | all-remote |
| ashby | `notion` | 129 | 85 | 3 US interns (pipeline signal) |
| ashby | `cursor` | 124 | 54 | remote roles |
| ashby | `preply` | 118 | 118 | all-remote edtech |
| ashby | `replit` | 76 | 76 | incl. Remote-Brazil role |
| ashby | `linear` | 31 | 31 | all-remote |
| ashby | `zapier` | 9 | 9 | South America/LatAm location |
| lever | `outreach` | 30 | 20 | remote roles |
| lever | `metabase` | 19 | 19 | all-remote BI |

Excluded (verified dead/empty/out-of-scope, never fetched): Greenhouse 404 —
`luizalabs`, `zup`, `dafiti`, `netshoes`, `globo`, `itau`, `bancointer`,
`neon`, `pagseguro`, `olxbrasil`, `warrenbrasil`, `ambevtech`, `bunq`;
empty boards — `nubank` on Greenhouse (migrated to Ashby), `loft` on Ashby,
`whoop`/`kapwing` on Lever, `remote` on Greenhouse (stale, 2 jobs — use
`remotecom`); trivial — `traderepublic` (1 job); non-BR — `kavak` (Mexico);
SmartRecruiters `ifood` probe returned empty (out of scope, future spike).
Empty/404 boards are skipped at runtime with a log line, never fail the fetch.

## 3. Design

Three small `ExtractionStrategy` classes — one per ATS (JSON shapes differ) —
sharing the `GupyProvider` pattern (constructor-injected `RestApiStrategy` +
`ExponentialBackoffRetry`, per-board loop, URL-keyed dedupe, per-board
try/catch so one dead board never fails the fetch). Registered in
`ProviderRegistry` as `greenhouse`, `ashby`, `lever`; existing providers
untouched. `JobNormalizer` unchanged (keywords, max-age, junior filters,
URL dedupe all apply downstream — ATS board APIs have no keyword search, so
providers fetch full boards and filtering stays in the normalizer).

```
GreenhouseProvider(boardTokens, displayNames, ...)  -> providerId "greenhouse"
AshbyProvider(boardNames, displayNames, ...)        -> providerId "ashby"
LeverProvider(sites, displayNames, ...)             -> providerId "lever"
```

Board tokens/sites and company display names come from `application.yaml`
via `@Value` in `AppConfig` (no other layer reads config):

```yaml
ats:
  enabled: false                       # default: idle until the §7 sample sign-off
  timeout-seconds: 30
  # WARNING: board lists MUST stay CSV, never YAML flow style ([a, b]):
  # @Value List<String> binding silently resolves flow style to an empty list
  # on Spring Boot 4.0.6 (proven live during §7 sample: 0 boards vs CSV working).
  greenhouse-boards: stone,xpinc,c6bank,gympass,quintoandar,ebanx,thoughtworks,vtex,feedzai,elastic,gitlab,remotecom,grafanalabs,mozilla,monzo,tailscale,wikimedia
  ashby-boards: nubank,notion,cursor,preply,replit,linear,zapier
  lever-sites: dlocal,outreach,metabase
  lever-page-size: 100                 # Lever pagination page size
  lever-max-pages: 3                   # hard cap per site (mirrors scraper.infojobs.max-pages)
  # Company label per board (Ashby/Lever responses carry no company).
  # WARNING: must stay a single-line SpEL map literal for @Value("#{${ats.display-names}}") —
  # a nested YAML map flattens into per-key properties and breaks the parse at boot.
  display-names: "{nubank: 'Nubank', notion: 'Notion', cursor: 'Cursor', preply: 'Preply', replit: 'Replit', linear: 'Linear', zapier: 'Zapier', dlocal: 'dLocal', outreach: 'Outreach', metabase: 'Metabase'}"
```

## 4. Field mapping → `RawJob`

(`RawJob`: all nullable except `url` + `source`. Blank title/url node → skip,
same as `GupyProvider.mapNode`.)

### Greenhouse — `GET https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true`

| RawJob | Source | Notes |
|---|---|---|
| title | `title` | direct |
| company | `company_name` | strip ` - <suffix>` (e.g. `"Stone - Linkedin"` → `"Stone"`) |
| url | `absolute_url` | direct, non-blank else skip |
| description | `content` | **requires `?content=true`**; HTML **double-encoded** (`&amp;lt;`) → decode twice then strip tags; worst case ~3.8 MB for 400-job board (accepted, single request, no N+1) |
| rawDate | `first_published` | ISO 8601, truncate to `yyyy-MM-dd` (Gupy parity) |
| location | `location.name` | e.g. `"Híbrido, São Paulo"` |
| workModel | inferred | no explicit flag → `"Remoto"` if location/description contains remoto/remote, `"Híbrido"` if híbrido/hybrid, else null (never block on unknown) |
| source | `"greenhouse"` | hardcoded |

No pagination (single response + `meta.total`). Rate limiting: 429 responses are
retried via the existing `ExponentialBackoffRetry` (fixed exponential backoff +
jitter on the retryable status). The `Retry-After` header is **not** read; an
exhausted 429 skips the board (PR#84 review P2-b, actual behavior).

### Ashby — `GET https://api.ashbyhq.com/posting-api/job-board/{board}`

| RawJob | Source | Notes |
|---|---|---|
| title | `title` | direct |
| company | config `display-names[board]` | **not in response** |
| url | `jobUrl` | direct, non-blank else skip |
| description | `descriptionPlain` | already plain text, no cleanup |
| rawDate | `publishedAt` | ISO 8601 → `yyyy-MM-dd` |
| location | `location` | direct |
| workModel | `isRemote` + `workplaceType` | `isRemote=true` or `workplaceType=Remote` → `"Remoto"`; `Hybrid` → `"Híbrido"`; else null |
| source | `"ashby"` | hardcoded |

No pagination. `employmentType: "Intern"` forwarded in `metadata` (`atsEmploymentType`)
as a junior hint for the normalizer (no scoring change in this spec).

### Lever — `GET https://api.lever.co/v0/postings/{site}?mode=json`

| RawJob | Source | Notes |
|---|---|---|
| title | `text` | direct |
| company | config `display-names[site]` | **not in response** |
| url | `hostedUrl` | direct, non-blank else skip |
| description | `descriptionPlain` | already plain text |
| rawDate | `createdAt` | **epoch millis** → `Instant.ofEpochMilli(...).atZone(UTC).toLocalDate()` |
| location | `categories.location` | may embed model (`"Sao Paulo (Hybrid)"` — leave as-is, workModel parsed separately) |
| workModel | `workplaceType` | `remote` → `"Remoto"`, `hybrid` → `"Híbrido"`, `on-site`/`unspecified` → null |
| source | `"lever"` | hardcoded |

`skip`/`limit` pagination available (default 100) — loop while full page.
Cache guidance (≥60 s) is bot-side concern, not needed for daily fetch.

## 5. Error policy (per board, Gupy parity)

- 404 / empty `jobs: []` → `log.warn`, skip board, continue.
- 429 → retry via `ExponentialBackoffRetry` (fixed exponential backoff + jitter;
  `Retry-After` is not honored), then skip the board on exhaustion.
- 5xx / malformed node → `log.error`, skip board, continue.
- A board failure never fails the whole provider fetch; an empty result is a
  valid (possibly empty) list, never null.

## 6. Tests (TDD, WireMock, no Spring context in unit tests)

Per provider: valid board (2+ jobs incl. junior title → mapped `RawJob`s),
empty board (`jobs: []` → empty list), 404 → skip, 429 → retry-then-skip,
blank title/url node → skipped. Plus mapping unit tests for the ATS-specific
transforms: Greenhouse double-decode + company suffix strip + epoch/date
handling, Lever epoch-millis conversion, workModel inference matrix
(Remoto/Híbrido/null). Naming `methodName_scenario_expectedResult` +
`@DisplayName`.

## 7. Acceptance criteria (from #76)

- [ ] Board list in §2 implemented; excluded boards documented, not fetched.
- [ ] Each provider returns title/company/url/description/postedAt/location
      through the `RawJob` contract; existing providers untouched (no
      `web/` or `domain/` diff, no Flyway migration — scores persist in the
      existing `match_score` column).
- [ ] WireMock suites green (valid / empty / 404 / 429); full `./mvnw test`
      green; no Spring in new unit tests.
- [ ] Junior-relevance: sample fetch shows junior/estágio/intern entries
      flowing through `JobNormalizer` (Stone/VTEX/EBANX signals in §2);
      provider idle (not default-enabled) until this sample is confirmed —
      enable flag `ats.enabled` (default `false` until sample sign-off, then
      `true`).
- [ ] `AppConfig` is the only `@Value` layer; records, no Lombok,
      conventional commits, SDD+TDD pair commits.

## 8. Out of scope

SmartRecruiters / Workday / Taleo / proprietary systems (iFood, Mercado
Livre, …) — separate future spikes. `companyWebsite` enrichment for ATS jobs
(no detail pages fetched in this spec). Salary/compensation mapping
(Ashby `includeCompensation` not requested). Changing cutoffs, prompts, or
scorer weights.
