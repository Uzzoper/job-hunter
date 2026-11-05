# LinkedIn posts ingest

> Status: spec (pre-implementation). Feature branch: `feat/linkedin-posts-ingest`
> (based on `dev`). Flow agreed 2026-11-04; implements the bot-collected feed
> pipeline. Out of scope: automatic sending; LinkedIn usage rules stay bot-side.

## Context

Posts from the LinkedIn feed that carry a contact e-mail can become `Job`
rows. The bot collects them (logged-in, on demand, under the feed rules:
3/day, 2h spacing, 30 items, stop at checkpoint, read-only) and calls the
backend, which validates, extracts fields and saves. No scraper/provider
code is involved — this is an application-layer ingest path; the row enters
with source `linkedin-post` and flows through analysis/e-mail exactly like
scraper-sourced jobs.

## Pipeline (cheap → expensive)

1. Validate body (400 on bad input, never calls AI)
2. No e-mail → `200 {ignored: "no-contact"}` (never calls AI)
3. URL duplicate → `200 {ignored: "duplicate"}` (never calls AI)
4. AI classification (ehVaga, title, company)
5. Validate AI output (non-blank, length-capped) → `200 {ignored:
   "invalid-extraction"}` when unparsable
6. Save + `201` with the `Job` (`503 {error: "ai-unavailable"}` when AI
   fails/times out — retryable, nothing saved)

## Contract

`POST /api/jobs/ingest` — bot token (`X-Bot-Token`) or JWT; body:

```json
{ "url": "https://.../feed/update/urn:li:activity:123", "text": "...",
  "author": "Empresas RJ", "postedAt": "2026-11-04" }
```

- `postedAt` optional, fallback: collection date. Respects the reuse rule:
  the bot sends the real post date when the DOM shows one ("3d" → resolved),
  otherwise null.
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
8. **Sucesso** — saves `Job{source: "linkedin-post", url: permalink,
   postedAt: bot-provided (fallback collection date), contactEmail,
   title/company from AI}` → `201` with the `Job` response.
9. **Domínio suspeito** — free-mail domain (gmail, hotmail, yahoo, outlook,
   ig, uol…) → saves with `emailSuspect = true`; flag appears in the job
   detail response.
10. **Auth** — no `X-Bot-Token`/JWT → `401`. Pinned by the existing
    `BotTokenFilterTest` (the chain covers every request by construction —
    `[BotTokenFilter, JwtTokenFilter, UPAF]`) — no duplicate web test.

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
- Post-2026-11-04 review hardening: permalink regex anchored (`\d+/?$` — a
  link with tracking params is by-construction invalid, so save-side dedupe
  cannot be bypassed), blank/unparsable AI reply counts as invalid-extraction
  (logged, narrow `JsonProcessingException` catch), free-domain match covers
  subdomains, `Job.withContactEmail/withCompanyWebsite` preserve
  `emailSuspect` (regression-pinned), `author`-in-prompt pinned by test.

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
- `ingest_whenAiFails_shouldThrowAiExceptionAndSaveNothing`
- `ingest_whenFreeEmailDomain_shouldSaveWithSuspectFlag`
- `ingest_whenValidPostWithBotPostedAt_shouldSaveLinkedinPostJob`
- `ingest_whenPostedAtMissing_shouldFallbackToCollectionDate`

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
- Backend rate limiting of ingest (bot-side LinkedIn rules are trusted;
  revisit only if abuse/accidents appear in evidence).
- Free-domain list beyond a starter constant in the use case.
