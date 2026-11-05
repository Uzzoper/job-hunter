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
   "invalid-extraction"}` when unparsable; the JSON payload is sliced from
   the first `{` to the last `}` so surrounding text and markdown fences do
   not break parsing
6. Save + `201` with the `Job` (`502 {error: "ai-unavailable"}` when AI
   fails/times out — retryable, nothing saved)

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
6. **IA indisponível** — AI fails or blows the timeout → `502 {error:
   "ai-unavailable"}` (fits the existing `GlobalExceptionHandler` mapping:
   `AiException → BAD_GATEWAY`); nothing saved, retryable.
7. **Saída da IA inválida** — `title`/`company` blank or above the length cap →
   `200 {ignored: "invalid-extraction"}`, nothing saved.
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
- `ingest_whenTextOverCap_shouldTruncateForPromptAndSaveWhileScanningFullText`
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
