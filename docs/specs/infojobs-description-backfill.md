# Spec: InfoJobs Description Backfill

> **Layer:** `application` (use case) + `infrastructure` (source adapter)
> **Status:** proposed — 2026-11-20
> **Related:** `infojobs-scraper.md` (see *Divergence*, below), `email-enrichment.md`

---

## Context

`InfoJobsProvider.extract()` enriches cards with their detail page, but only for
the first `scraper.infojobs.max-detail-fetch` cards of each fetch run
(`application.yaml`, default 100). Every card outside that window is persisted
with its list-card excerpt and flagged in the run statistics
(`detailSkippedCount`).

The excerpt is not a description: `InfoJobsProvider.extractDescription` cuts the
card text at the work-model token and keeps the tail, so a card that reads
`Desenvolvedor Java · Remoto · R$ 3.000` yields a description of a few dozen
characters.

This is permanent, not transient. The fetch path deduplicates by URL before the
enrichment window (`isKnownUrl`), so a stored row is never enriched on a later
run — a row that was born with a two-line excerpt keeps it forever.

### Measurement (live bot DB, 2026-11-20)

| | gupy | infojobs |
|---|---|---|
| fresh rows (30d) | 28 | 263 |
| score ≥ 40 | 16 (57%) | 18 (6.8%) |
| score ≥ 60 | 2 | 9 |

The gap is not market quality. 245 of the 263 InfoJobs rows carry descriptions
between 43 and 153 characters. `AiAnalysisService` builds its prompt from
`job.description()`, so those 245 scores were computed against a fragment.

The extraction itself is healthy — verified against a live detail page on
2026-11-20:

| check | result |
|---|---|
| `div.js_vacancyDataPanels p.text-break` matches | yes — 893 chars of real body |
| JSON-LD `JobPosting.description` fallback | 1102 chars |
| detail fetch time (3 runs) | 0.86–0.93 s vs the 5 s timeout |
| bot challenge | none |

So the selector, the timeout and the fallback all work. The rows were simply
never fetched.

---

## Goal

Give stored InfoJobs rows a full description without a schema change and without
re-scraping the whole source, and report how many rows the run repaired.

---

## Contract

### New endpoint

```
POST /api/jobs/backfill-descriptions
     ?dryRun=true            (default true)
     &limit=100              (default 100, max 200)
     &maxDescriptionChars=300 (default 300, min 0)
     &afterId=<long>         (optional; exclusive lower bound on job id)
```

Response `200`:

```json
{ "scanned": 100, "updated": 87, "stillShort": 13, "lastId": 6291 }
```

- `scanned` — candidate rows inspected in this run.
- `updated` — rows whose description was replaced (dry-run reports the same
  number without persisting, so the preview is honest).
- `stillShort` — candidates the source could not improve; `scanned - updated`.
- `lastId` — id of the last candidate scanned (`0` when none), so the caller
  pages the next call with `afterId=lastId` until `scanned = 0`.

### Candidate query

Rows where `source = 'infojobs'` **and** `LENGTH(description) < maxDescriptionChars`,
ordered by `id` ascending, at most `limit`, with `id > afterId`.

Existing long descriptions are never candidates, so nothing can overwrite a
description that is already good. `afterId` exists because a row whose detail
page yields nothing stays short forever; without paging it would be retried by
every run and starve everything behind it on a small `limit`.

### Update rule

A candidate is updated only when the detail page yields text **longer than the
current description**. `Job` identity stays URL-based; the update is a
`withDescription(...)` copy that preserves every other field.

A source failure (timeout, HTTP error, bot challenge, unparsable page) leaves the
row untouched, is logged at DEBUG, and does **not** abort the run: one dead URL
must not cost the other 99.

---

## Architecture

```
web            JobController                     POST /api/jobs/backfill-descriptions
application    BackfillJobDescriptionsUseCase    port/in, Result record
application    BackfillJobDescriptionsService    scan → fetch → compare → save
application    JobDescriptionSourcePort          port/out: Optional<String> fetchDescription(String url)
infrastructure InfoJobsDescriptionSource         adapter over the provider's detail fetch
infrastructure InfoJobsProvider                  exposes the detail extraction
infrastructure JobJpaRepository                  query + adapter method
domain         Job.withDescription(String)        field-preserving copy
```

- Mirrors `BackfillCompanyWebsitesService` (same shape: `dryRun`, `afterId`,
  scan set defined by a query, no duplication of the fetch path's policy).
- `JobDescriptionSourcePort` is source-agnostic on purpose. The first
  implementation targets InfoJobs because it is the only provider whose detail
  page carries a description the card does not; Gupy already receives the full
  text from its JSON API.
- `InfoJobsProvider` gains `Optional<String> fetchDetailDescription(String url)`
  built from the same private `fetchDetailHtml` (rate limiter, retry,
  bot-challenge detection) and the same selector + JSON-LD fallback used by
  `mergeDetail`. The extraction is factored into one method so the fetch path and
  the backfill cannot drift; `mergeDetail` keeps its "longer text wins" rule.
- Sequential fetch: the detail bucket is 5 permits/sec and one page takes ~0.9 s,
  so parallelism buys nothing. `limit` is the run budget; `afterId` is the
  continuation.
- **No migration**: `jobs.description` already exists and is already `TEXT`.

---

## Tests (RED first, plain JUnit 5 + Mockito)

### Domain

| test | expectation |
|---|---|
| `withDescription_whenCalled_shouldReplaceOnlyDescription` | id, url, title, company, postedAt, source, contactEmail, companyWebsite, emailSuspect unchanged |

### Application — `BackfillJobDescriptionsServiceTest`

| test | expectation |
|---|---|
| `run_whenDryRun_shouldReportUpdatesWithoutSaving` | `updated` counted, `save` never called |
| `run_whenSourceReturnsLongerText_shouldSaveWithNewDescription` | saved job carries the new text |
| `run_whenSourceReturnsShorterText_shouldKeepCurrentDescription` | no save; counted `stillShort` |
| `run_whenSourceReturnsEmpty_shouldCountStillShortAndContinue` | remaining candidates still processed |
| `run_whenSourceThrows_shouldContinueWithTheRest` | exception swallowed, other rows updated |
| `run_whenLimitReached_shouldScanOnlyLimit` | query receives the limit; `scanned == limit` |
| `run_whenAfterIdProvided_shouldForwardIt` | query receives `afterId`; omitted → `0` |
| `run_whenNoCandidates_shouldReturnZeroes` | `scanned=0, updated=0, stillShort=0, lastId=0` |

### Infrastructure — `InfoJobsDescriptionSourceTest` (WireMock)

| test | expectation |
|---|---|
| `fetchDescription_whenDetailPageHasDescription_shouldReturnBody` | 893-char `<p>` returned |
| `fetchDescription_whenSelectorMissing_shouldFallBackToJsonLd` | JSON-LD text returned |
| `fetchDescription_whenPageHasNeither_shouldReturnEmpty` | `Optional.empty()` |
| `fetchDescription_whenBotChallenge_shouldReturnEmpty` | no throw |

### Web — `JobControllerTest`

| test | expectation |
|---|---|
| `backfillDescriptions_whenCalled_shouldReturn200WithCounts` | body matches `{scanned, updated, stillShort, lastId}` |

---

## Divergence from `infojobs-scraper.md`

That spec is a pre-implementation document: it describes `InfoJobsScraper`,
`ScraperPort` and `CompositeScraper`, and states in *Out of scope* that detail
pages are not fetched. The delivered code is `InfoJobsProvider` behind
`ProviderRegistry`, and it does fetch detail pages with a JSON-LD fallback. This
spec documents the current behaviour and adds the backfill for the rows the
per-run window left behind; it does not rewrite the historical document.

---

## Out of scope

- **Persisting `location` / `workModel`.** Both are extracted (`RawJob`) and
  dropped at `JobNormalizer`. Persisting them would make "is this remote?"
  answerable by the API and is a natural companion to this backfill — the detail
  page is already being fetched — but it needs a migration, a domain field and a
  DTO change. Separate spec.
- **Raising `max-detail-fetch`.** The backfill drains the existing backlog; the
  per-run cap and its rate-limiting intent stay as they are. A future fetch that
  overflows 100 cards is still drained by the same backfill.
- **Re-scoring.** The backfill only repairs text. Re-analysis is the existing
  `POST /api/jobs/{id}/analyze` per row, which already upserts by
  `(job_id, user_id)`.
- **Other sources.** Gupy, Greenhouse, Ashby and Lever receive full text from
  their JSON APIs; no backfill is needed.
