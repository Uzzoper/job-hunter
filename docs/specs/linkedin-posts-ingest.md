# LinkedIn posts ingest

> Status: implemented in `feat/linkedin-posts-ingest` (PR #106 → `dev`); this
> revision is the 2026-11-05 hardening round (injection fence, strict URL
> canonicalization, tolerant AI parsing, duplicate race, dedicated profile
> option). Out of scope: automatic sending; LinkedIn usage rules stay bot-side.

## Context

Posts from the LinkedIn feed that carry a contact e-mail can become `Job`
rows. The bot collects them (logged-in, on demand, under the feed rules:
3/day, 2h spacing, 30 items, stop at checkpoint, read-only) and calls the
backend, which validates, extracts fields and saves. No scraper/provider
code is involved — this is an application-layer ingest path; the row enters
with source `linkedin-post` and flows through analysis/e-mail exactly like
scraper-sourced jobs.

## Pipeline (cheap → expensive)

1. Validate body (400 on bad input, never calls AI): permalink shape
   (strict host regex) and non-blank text; the permalink is then
   canonicalized to `https://www.linkedin.com/feed/update/urn:li:activity:{id}/`
2. No e-mail → `200 {ignored: "no-contact"}` (never calls AI) — e-mail scan
   runs on the FULL text, capping is a prompt/row-size concern only
3. URL duplicate (canonical form) → `200 {ignored: "duplicate"}` (never
   calls AI)
4. AI classification (ehVaga, title, company) — the prompt fences the
   untrusted post content between `<post>` tags with an explicit
   never-follow-instructions instruction
5. Validate AI output (non-blank, length-capped) → `200 {ignored:
   "invalid-extraction"}` when unusable; the JSON payload is sliced from
   the first `{` to the last `}` so surrounding text and markdown fences do
   not break parsing. `ehVaga` must be a boolean (absent/malformed field →
   invalid-extraction — only an explicit false is `not-a-job`); `title`
   must be textual, non-blank and ≤ the cap; `company` is OPTIONAL —
   absent/null/non-textual/blank/over-cap falls back to the visible
   placeholder "Empresa não informada" (WARN-logged), because curators'/
   recruiters' shares without a named hiring company are a valid vacancy
   class (incident 2026-11-07)
6. Save + `201` with the `Job` (`502` with the shared error body
   `{status: 502, error: "Bad Gateway", message: <AiException message>}` —
   the `GlobalExceptionHandler` mapping, retryable; nothing saved) when AI
   fails/times out

## Contract

`POST /api/jobs/ingest` — bot token (`X-Bot-Token`) or JWT; body:

```json
{ "url": "https://.../feed/update/urn:li:activity:123", "text": "...",
  "author": "Empresas RJ", "postedAt": "2026-11-04" }
```

- `url` may carry `www.`/two-letter-country subdomain and a trailing slash;
  it is normalized to the canonical `https://www.linkedin.com/.../{id}/`
  form for dedupe and storage.
- `postedAt` optional; a future date (relative to the server clock falls
  back to the collection date, which also covers null.
- Text longer than 3000 chars is truncated for the AI prompt and for
  `Job.description` (blank text still 400) — the e-mail scan keeps the full
  text, so an e-mail after the cap still counts.
- Auth: `BotTokenFilter` is already first in the chain
  (`[BotTokenFilter, JwtTokenFilter, UPAF]`) and `JwtTokenFilter` skips when
  the bot authenticated — the route is covered by `anyRequest().authenticated()`
  (401 otherwise).

## Scenarios

1. **Body inválido** — permalink missing/outside `/feed/update/urn:li:activity:{id}`
   or blank text → `400`, no AI call.
2. **Post sem e-mail** — `EmailExtractorPort` finds nothing →
   `200 {ignored: "no-contact"}`, no AI call.
3. **Duplicado** — `JobRepository` already has the URL →
   `200 {ignored: "duplicate"}`, no AI call.
4. **Vários e-mails** — reuses `ContactEmailExtractorPort` as-is: the post
   text is passed as a single scan blob and the port's ranked-winner rule
   decides (shared `EmailExtractor`: `mailto:` pass > regex pass, hiring
   local-parts (`vagas`, `vaga`, `rh`, `recrutamento`, …) beat
   generic/`contato`/personal, position order breaks ties). The rule itself
   is already pinned by `EmailExtractorRecallMeasurementTest`; the service
   test only pins that the winner's email is what gets persisted.
5. **Não é vaga** — AI returns `ehVaga=false` → `200 {ignored: "not-a-job"}`, nothing saved.
6. **IA indisponível** — AI fails or blows the timeout → `502` with the shared
   error body `{status: 502, error: "Bad Gateway", message:
   <exception message>}` (no special-cased payload: the `GlobalExceptionHandler`
   header is global for every `AiException` consumer — analysis, e-mail,
   ingest); nothing saved, retryable.
7. **Saída da IA inválida** — `title` non-textual/blank/over the cap, or
   `ehVaga` missing/not a boolean → `200 {ignored: "invalid-extraction"}`,
   nothing saved. `company` absent/null/non-textual/blank/over-cap is NOT
   an invalid reply: the row saves with the placeholder "Empresa não
   informada" (spec amendment 2026-11-07). (A permalink absent from the
   request body is still a 400, never a 500 — the null URL reaches
   validation, not the regex engine.)
8. **Sucesso** — saves `Job{source: "linkedin-post", url: canonical
   permalink, postedAt: bot-provided or collection date (never future),
   contactEmail, title/company from AI, description: text truncated at
   3000}` → `201` with the `Job` response.
9. **Domínio suspeito** — free-mail domain (gmail, hotmail, yahoo, outlook,
   ig, uol…) → saves with `emailSuspect = true`; flag appears in the job
   detail response.
10. **Auth** — no `X-Bot-Token`/JWT → `401`. The filter contract stays
    pinned by `BotTokenFilterTest`; this spec additionally pins the ROUTE
    against the real chain with `JobIngestControllerSecurityTest`
    (`[BotTokenFilter, JwtTokenFilter, UPAF]`, service-token properties,
    401 + no use-case interaction).

Body field `author` is classifier context only (feeds the AI prompt for
better `ehVaga`/title/company extraction); it is NOT persisted — `Job` has
no author, and inventing storage was not justified.

Infrastructure facts supporting the scenarios:
- `ContactEmailExtractorPort` (`application/port/out/`) already exposes the
  shared pipeline (consumers: `GithubJobsProvider`,
  `CompanySiteEnricher`, `BackfillContactEmailsService`) — no new
  extraction infra.
- `Job` record enforces `url/postedAt/source` non-null; title/company need
  explicit validation in the use case (scenario 7 caps: 200 chars title,
  120 chars company, 3000 chars post text; blank → invalid-extraction).
- 2026-11-04 review hardening: blank/unparsable AI reply counts as
  invalid-extraction (narrow `JsonProcessingException` catch), free-domain
  match covers subdomains, `Job.withContactEmail/withCompanyWebsite`
  preserve `emailSuspect` (regression-pinned), `author`-in-prompt pinned.
- 2026-11-05 hardening round: strict host regex + canonicalization (the old
  suffix-matching regex accepted `evillinkedin.com` — reviewer-found);
  prompt fencing neutralizes `<`/`>` and `"` and author line breaks, so a
  post cannot close the `<post>` delimiter; AI replies are JSON-sliced
  before `readTree`; a duplicate race (checked against the `jobs.url`
  UNIQUE constraint from V1) is converted in the persistence adapter into
  the domain `DuplicateUrlException` and surfaced as `Duplicate` — other
  integrity violations rethrow unchanged; a dedicated gateway profile is
  supported via the optional `hermes.ingest-base-url` property (falls back
  to the shared profile with a startup WARN when absent — the profile
  itself is provisioned on the VM, not in this repo, per the 2026-11-05
  requirement that the shared `jobhunter-bot` profile may carry gateway-side
  memory/toolsets the code cannot inspect).

## Schema (Flyway)

`V12__add_email_suspect_column.sql` — `jobs.email_suspect BOOLEAN NOT NULL
DEFAULT 0` (SQLite friendly, snake_case per house rules), JPA adapter +
domain `Job` record gain the field. Naming: `emailSuspect` in domain/DTOs.

## Test plan (RED, before any production code)

Application (`IngestLinkedInPostServiceTest`, plain Mockito):
- `ingest_whenTextHasNoEmail_shouldReturnNoContactWithoutCallingAi`
- `ingest_whenUrlAlreadySaved_shouldReturnDuplicateWithoutCallingAi`
- `ingest_whenExtractorReturnsRankedEmail_shouldPersistWinningEmail`
- `ingest_whenAiReturnsNotJob_shouldReturnNotJobAndSaveNothing`
- `ingest_whenAiOutputMissingTitleOrCompany_shouldReturnInvalidExtractionAndSaveNothing`
- `ingest_whenAiTitleOverCapOrBlankReply_shouldReturnInvalidExtraction`
- `ingest_whenAiReplyBlank_shouldReturnInvalidExtraction`
- `ingest_whenAiFails_shouldThrowAiExceptionAndSaveNothing`
- `ingest_whenFreeEmailDomain_shouldSaveWithSuspectFlag`
- `ingest_whenValidPostWithBotPostedAt_shouldSaveLinkedinPostJob`
- `ingest_whenPostedAtMissing_shouldFallbackToCollectionDate`
- `ingest_whenPostedAtInFuture_shouldFallbackToCollectionDate`
- `ingest_whenTextOverCap_shouldTruncateForPromptAndRowWhileEmailScansFullText`
Hardening-round additions:
- `ingest_whenPromptBuilt_shouldFencePostAndInstructNeverToFollow`
- `ingest_whenTextClosesThePostTag_shouldStayNeutralizedInPrompt`
- `ingest_whenAuthorNull_shouldOmitAttributeInsteadOfPrintingNull`
- `ingest_whenUrlVariantsDifferOnlyInSubdomainOrSlash_shouldDedupeCanonical`
- `ingest_whenUrlIsEvilHostOrHttp_shouldThrow400Mapping`
- `ingest_whenAiReplyFencedOrProseWrapped_shouldStillClassifyVacancy`
- `ingest_whenAiReplyHasNoJsonObject_shouldReturnInvalidExtraction`
- `ingest_whenSaveRacesWithDuplicateInsert_shouldReturnDuplicate`
Wiring (`IngestAiProfileWiringTest`, context runner, infrastructure/config):
- with `hermes.ingest-base-url` → dedicated `HermesAgentClient` (observable
  `baseUrl()`), no WARN; without → shared-profile fallback + startup WARN.
Persistence (`JobPersistenceAdapterTest`, plain Mockito):
- converts a `DataIntegrityViolationException` carrying
  `UNIQUE constraint failed: jobs.url` into `DuplicateUrlException`; other
  violations rethrow unchanged.
Security (new, real chain): `JobIngestControllerSecurityTest` — POST
`/api/jobs/ingest` without `X-Bot-Token` or JWT → 401 (pattern of
`BotTokenFilterTest`: `@Import` SecurityConfig + both filters,
service-token `@TestPropertySource`).

Web (`JobIngestControllerTest`, `@WebMvcTest`):
- `ingest_whenPermalinkIsNotLinkedInFeed_shouldThrowIllegalArgument` +
  `ingest_whenBlankText_shouldThrowIllegalArgument` (application) and
  `ingest_whenBodyValidationFails_shouldReturn400` (web maps the 400)
- `ingest_whenSaved_shouldReturn201WithJob`
- `ingest_whenNoContact_shouldReturn200WithIgnoredBody`
- `ingest_whenAiUnavailable_shouldReturn502`
- 401 is NOT re-tested here: `BotTokenFilterTest` already pins the filter,
  and the chain covers the route by construction. The web layer pins the
  authenticated happy path via the shared `authenticateAs` helper.

New contracts introduced by the tests (interfaces + records only, no
behavior): `IngestLinkedInPostUseCase` (port/in),
`IngestPostCommand{url, text, author, postedAt}`, sealed
`IngestResult{NoContact, Duplicate, NotJob, InvalidExtraction, Saved}`.

## Out of scope

- Automatic sending; the analysis/e-mail/approval flow is unchanged.
- Provisioning the dedicated Hermes profile on the VM — the repo only adds
  the optional property; profile creation and its toolset/memory hygiene
  belong to the VM owner (2026-11-05 decision).
- Backend rate limiting of ingest (bot-side LinkedIn rules are trusted;
  revisit only if abuse/accidents appear in evidence).
- Free-domain list beyond a starter constant in the use case.

## Amendment 2026-11-20 — relevance gate + vacancy-level dedupe

Two defects measured on the live bot DB (11 stored `linkedin-post` rows, 90d):

1. **7 of 11 are non-dev** (administrative assistant, mechanical designer, hotel
   maintenance, support analyst, SAP consultant, two generic "Vagas de Emprego"
   posts). The classification prompt asks only `ehVaga`, so every vacancy post
   carrying an e-mail is saved regardless of role or seniority.
2. **Two rows are the same vacancy** — ids `6239`/`6240`, identical title
   (`Desenvolvedor(a) Full Stack Júnior`) and contact (`funilfaixapreta@gmail.com`),
   distinct permalinks because a different author re-shared the post.
   `existsByUrl` cannot see this: permalink identity is per-post, not per-vacancy.

### A. Relevance gate (same AI call, no extra cost)

The prompt gains two fields:

| field | values | effect |
|---|---|---|
| `roleFit` | `dev` \| `nao_dev` \| `indefinido` | `nao_dev` → reject |
| `seniority` | `junior` \| `pleno` \| `senior` \| `indefinido` | `pleno`/`senior` → reject |

Rule: save only when `ehVaga = true` **and** `roleFit != nao_dev` **and**
`seniority ∉ {pleno, senior}`. Otherwise `200 {ignored: "not-relevant"}`.

- `indefinido` **always passes** — short posts rarely state level, and rejecting
  ambiguity would kill the little volume this channel has.
- A missing or non-conforming field is treated as `indefinido`, so an older
  prompt reply still saves (backward compatible, never rejects on absence).
- The gate runs after the AI call (it needs `roleFit`); it saves rows, not tokens.

### B. Vacancy-level dedupe

After the AI classification and the relevance gate, dedupe on **(normalized
contact e-mail, normalized title)** within a 30-day window:

- e-mail: trim + lowercase;
- title: trim + lowercase + accent-stripped + punctuation collapsed to spaces.

Match → `200 {ignored: "duplicate"}`, nothing saved.

- No migration: the port gains `List<Job> findByContactEmail(String email)` and
  the use case applies the 30-day window and the normalized-title comparison in
  memory. The window lives in the service, not the query, so the boundary is
  pinned by a unit test instead of by SQL (volumes are tiny; a normalized column
  is premature).
- Accepted trade-off: two genuinely distinct openings with the same title from
  the same address inside 30 days collapse into one. The same address means the
  same application target anyway.

### C. Contract changes

- `IngestResult` sealed interface gains `NotRelevant`.
- `JobIngestController` maps it to `200 {"ignored": "not-relevant"}`, matching
  the existing ignored reasons.

### D. Tests (RED first, plain JUnit 5 + Mockito)

| test | expectation |
|---|---|
| `ingest_whenRoleNotDev_shouldReturnNotRelevantAndSaveNothing` | no save |
| `ingest_whenSeniorityPleno_shouldReturnNotRelevantAndSaveNothing` | no save |
| `ingest_whenSenioritySenior_shouldReturnNotRelevantAndSaveNothing` | no save |
| `ingest_whenRoleIndefinido_shouldSave` | ambiguity passes |
| `ingest_whenNewFieldsAbsentFromAiReply_shouldSave` | backward compatible |
| `ingest_whenSameVacancyDifferentPermalink_shouldReturnDuplicateWithoutSaving` | 30d window, same e-mail+title |
| `ingest_whenSameTitleDifferentEmail_shouldSave` | e-mail is part of the key |
| `ingest_whenSameVacancyOlderThan30Days_shouldSave` | window boundary is exclusive |

### E. Volume is not addressed here

The channel's supply is the feed, not the classifier: the only recorded pass read
9 posts, found 0 with an e-mail, and ingested 0. This amendment raises precision
(11 rows → ~3 dev rows) and removes double-application risk. Growing the channel
requires bot-side feed coverage and is explicitly out of scope.
