# Editable search keywords (issue #79, phase 1)

Single-owner self-hosted scope. Multi-user tenancy is out (see issue #79).
`user-preferences.md` deferred scraper filtering — this spec supersedes that line.

## Goal

Search keywords stop being committed-yaml-only. The owner edits one keyword
list at runtime via `PUT /api/profile`, and fetch resolves that list
(yaml stays the default when unset). No per-area profiles here — that is
issue #79 phase 2 (deferred, YAGNI trigger documented there).

## Slices (implement in order, each testable alone)

- **Slice A (cheap) — storage:** persist `keywords` with preferences; expose
  in `PUT`/`GET /api/profile`. Fetch still uses the yaml list.
- **Slice B (expensive) — resolution:** fetch resolves keywords per user at
  request time (stored list or yaml default). Replaces startup-bound lists.

## Slice A contract

- `UserPreferences` gains `List<String> keywords` (nullable in, normalized
  like `excludedCompanies` via the existing `normalizeList` helper):
  `MAX_KEYWORDS = 30`, `MAX_KEYWORD_LENGTH = 100`, blank items dropped.
- `hasContent()` returns true when keywords are non-empty.
- `PreferencesRequest` gains `keywords` with `@Size(max = 30)` items
  `@Size(max = 100)` each; `PreferencesResponse` exposes it back.
- Preserve-on-null semantics unchanged: prefs object omitted/null = keep;
  present = all sub-fields authoritative (so sending `keywords` replaces the
  whole list — clients should GET-merge-PUT).
- Migration `V11__add_keywords_column.sql`: additive only,
  `ALTER TABLE user_profiles ADD COLUMN keywords TEXT` (JSON list via
  converter, same pattern as V7 `work_preference TEXT`). NULL = unset.
- Entity + adapter map the column both ways; all-null prefs still map to
  null (existing `buildPreferences` behavior).

## Slice B contract

- New application outbound port `KeywordResolver`: `resolve(userId)` returns
  the stored list, or empty when unset. Per-provider yaml lists remain the
  fallback at each call site (a single global default cannot reproduce the
  differing per-provider yaml lists byte-identically). Single owner today,
  so no tenancy logic — userId only keys the lookup.
- Providers (`GupyProvider`, `InfoJobsProvider`, `LinkedInProvider`,
  `LinkedInScraperClient`, both `JobNormalizer` beans, `GupyMcpProvider`)
  keep their constructor-bound `final` defaults and use them when the
  override is empty; otherwise they use the override. `userId` crosses one
  boundary only (controller → use case); below that only the resolved
  immutable `List<String>` travels.
- `FetchJobsService`/`FetchSourceJobsService` receive `userId`
  (from `CurrentUserService`, same as other use cases); `POST /api/jobs/fetch*`
  endpoints pass the authenticated id through.
- No behavior change when keywords unset: byte-identical fetch to today.
  Do NOT "fix" neighboring quirks in this slice (e.g. single-source fetches
  sharing one normalizer) — preserve byte-identical, flag separately.

## Tests (TDD, RED first; names `method_scenario_expected` + `@DisplayName`)

- domain: `UserPreferencesTest` — normalization (blanks dropped, caps),
  `hasContent` with keywords only.
- application: `UserProfileServiceTest` — preserve/override with keywords;
  resolver default-vs-stored (Mockito ports, no Spring).
- web: `ProfileControllerTest` — PUT/GET round-trip of keywords, 400 on
  oversize items (`@WebMvcTest`).
- infrastructure: extend `SqliteBaselineIntegrationTest` style round-trip
  for the V11 column; WireMock provider tests assert the resolved list is
  the one queried.

## Out of scope

- Per-area profiles (`?profile=ml`) — issue #79 phase 2.
- Non-dev prompt/template neutrality — dev-first stays.
- Changing fetch quotas (`ceil(max-jobs / keywords.size())`) — untouched.
