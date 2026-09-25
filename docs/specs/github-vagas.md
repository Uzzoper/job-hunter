# GitHub Vagas Provider (frontendbr / backend-br) — Spec

Issue: TBD (sprint 2, after #76) · Status: proposed (awaiting approval — no code without it)

## 1. Context

`frontendbr/vagas` and `backend-br/vagas` are community job boards run as
GitHub issues: each open issue is one job, with structured labels
(Remoto/Híbrido/CLT/PJ + seniority + tech) and free-text bodies that carry
application emails in ~60% of frontend posts. The GitHub Issues REST API is
public, no-auth JSON at low volume — same category as the ATS board APIs
(#76): fetch, don't scrape. Verified live 2026-09-24.

Measured (first page + search-API totals):

| Repo | Open issues | With email in body | Remote signal | Junior signal |
|---|---|---|---|---|
| `frontendbr/vagas` | 33 | 18/30 sampled | 27/30 (label Remoto 24) | Estágio label 1 + sparse titles |
| `backend-br/vagas` | 47 | 2/30 sampled | 20/30 (label Remoto 15) | sparse (Sênior 19 dominant) |

Labels observed (frontendbr): Remoto 24, CLT 19, PJ 19, Híbrido 16, Sênior 15,
Pleno 11, Especialista 4, Estágio 1. Titles carry prefixes like
`[Remoto]`, `[Híbrido -SP]` and company suffixes (`- Evertec`).

## 2. Design

One `GithubJobsProvider` (`providerId "github"`) iterating configured repos
— same multi-board pattern as the ATS providers (constructor-injected
`RestApiStrategy` + `ExponentialBackoffRetry`, per-repo loop, URL-keyed
dedupe, per-repo try/catch so one dead repo never fails the fetch).
Registered in `ProviderRegistry`; existing providers untouched.
`JobNormalizer` unchanged (keywords, max-age, junior filters, URL dedupe,
`EmailExtractor` for the body emails — no new email logic in the provider).

```yaml
github:
  enabled: false                       # default: idle until §6 sample sign-off
  timeout-seconds: 30
  repos: frontendbr/vagas,backend-br/vagas   # CSV (NOT flow style — see ats-provider.md CSV note)
```

Config via `@Value` in `AppConfig` only; `github.enabled` gates the bean
(ATS pattern).

## 3. Requests

- `GET https://api.github.com/repos/{owner}/{repo}/issues?state=open&per_page=100`
  (both repos fit one page today; follow RFC 5988 `Link: rel="next"` while
  present, same loop discipline as Lever pagination).
- No auth (60 req/hr unauthenticated; a fetch costs ~1 req/repo — far below).
  No token in this spec (future: optional `github.token` for 5000/hr).
- `Accept: application/vnd.github+json`, `User-Agent` set (GitHub requires it).
- Rate-limit hit returns **403** (not 429) with `X-RateLimit-Reset` — NOT
  matched by the retry token list, so it falls into per-repo catch →
  warn + skip (accepted; documented, not retried).

## 4. Field mapping → `RawJob`

(`RawJob`: all nullable except `url` + `source`. Nodes with `pull_request`
key are PRs, not jobs → skip. Blank title/url → skip, Gupy parity.)

| RawJob | Source | Notes |
|---|---|---|
| title | `title` | direct (keeps `[Remoto]` prefixes — normalizer strips/uses) |
| company | parsed, best-effort | title suffix after last ` - ` / ` na ` / ` @ ` (e.g. `... - Evertec` → `Evertec`); null when absent (backend-br titles often lack it — body may carry it, normalizer's job, not provider's) |
| url | `html_url` | direct, non-blank else skip; this is the job identity for dedupe |
| description | `body` | raw markdown as-is (emails stay in text for `EmailExtractor` downstream; no provider-side parsing) |
| rawDate | `created_at` | ISO 8601 → `yyyy-MM-dd` (Gupy parity) |
| location | labels + title | label `São Paulo`, `Remoto`, `Híbrido` joined; title `[Híbrido -SP]` prefix parsed when labels lack it |
| workModel | labels/title | `Remoto` label or `[Remoto` prefix → `"Remoto"`; `Híbrido` → `"Híbrido"`; else null (never block) |
| source | `"github"` | hardcoded |
| metadata | labels + number | `labels` = comma-joined label names (carries seniority/tech signals downstream); `issue` = number |

## 5. Error policy (per repo, ATS parity)

- 404 / empty list → `log.warn`, skip repo, continue.
- 403 rate-limit / 5xx / malformed node → `log.error`, skip repo, continue.
- A repo failure never fails the whole provider fetch; empty result is valid.

## 6. Tests (TDD, WireMock, no Spring context in unit tests)

Valid repo (3+ issues incl. one PR node to prove PR-exclusion, one blank-title
skip, junior + remote labels) → mapped `RawJob`s; empty list → empty;
404 → skip; 429 → retry-then-skip; company-parse matrix (suffix forms +
absent → null); workModel matrix (label/prefix/none). Naming
`methodName_scenario_expectedResult` + `@DisplayName`.

## 7. Acceptance criteria

- [ ] Both repos fetched through the `RawJob` contract; existing providers
      untouched (no `web/` or `domain/` diff, no Flyway migration).
- [ ] WireMock suites green (valid / empty / 404 / 429 / PR-exclusion);
      full `./mvnw test` green; no Spring in new unit tests.
- [ ] Sample fetch shows remote + junior entries with emails flowing to
      `EmailExtractor` (frontendbr 60% baseline); provider idle
      (`github.enabled: false`) until sample sign-off, then `true`.
- [ ] `AppConfig` is the only `@Value` layer; CSV lists (never flow style);
      records, no Lombok, conventional commits, SDD+TDD pair commits.

## 8. Out of scope

GitHub auth token, other vagas repos (react-brasil, vuejs-br, androiddevbr —
one-line yaml additions later, same code), posting/commenting on issues,
Vagas.com volume provider (separate future spec), cutoff/prompt/scorer changes.
