# EmailExtractor Recall Audit + Improvement — Spec

Issue: #75 · Status: proposed (awaiting approval — no code without it)

## 1. Context

`EmailExtractor` (shared by `JobNormalizer` and `CompanySiteEnricher`) is
already a two-pass pipeline: `mailto:` anchors first (DOM order), then regex
over entity-decoded plain text on a de-obfuscated copy, with exclusions
(noreply/donotreply/no-reply/apply) and placeholder domains. What is NOT
known: recall on real descriptions — obfuscated forms beyond the covered set,
multiple addresses per text (which one wins), role vs personal addresses.

## 2. Phase 1 — audit (measure before touching code)

Build a committed fixture set `src/test/resources/email-fixtures/` of 30+
REAL anonymized description/title excerpts sampled from the database,
covering: plain email, `mailto:` link, `[at]`/`(at)`/`[dot]`/`(dot)` forms,
bare-word forms (`arroba`, `ponto`), zero-width tricks, multiple addresses
in one text, no-email texts, excluded shapes (noreply, placeholders).
Each fixture records the expected winner (or none).

Commit a recall-measurement test that reports, without changing behavior:
`recall = found_expected / texts_with_email`, `precision = correct_pick /
any_pick`, `false_positive = wrong_pick / texts_without_email`. These three
numbers are the baseline and go into §5. This phase changes no production code.

## 3. Gaps to close (Phase 2, TDD per gap)

1. **Bare `arroba` / `ponto` words** — `vaga arroba empresa ponto com`
   (no brackets) currently missed. Add word forms mirroring the existing
   bracket/parentheses pairs. Careful: bare `at` already matches — keep the
   same conservative spacing (`\s+arroba\s+`, `\s+ponto\s+`) so normal prose
   ("ponto de encontro") never fires without an `@` forming.
2. **Multi-address ranking** — today the FIRST valid match wins. Rank
   candidates by local-part: hiring-specific (`vagas`, `vaga`, `rh`,
   `recrutamento`, `selecao`, `talentos`, `carreiras`, `jobs`, `hiring`,
   `careers`, `people`, `talent`, `talent acquisition`) beats generic
   (`contato@`, `info@`, `sac@`, personal `nome.sobrenome@`). Order within
   pass stays title-before-description; `mailto:` pass still outranks regex.
3. **Keep the guarantees** — exclusions and placeholder domains unchanged;
   literal matches only, zero invented addresses (an address persists only
   if its exact characters occur in the text post-deobfuscation).

## 4. Non-goals

Guessing addresses, external validation (no SMTP checks), new sources,
changing the two-pass architecture, touching `CompanySiteEnricher` callers
(they share the pipeline and inherit improvements untouched).

## 5. Phase 3 — backfill with dry-run (DB-level proof)

New `BackfillContactEmailsUseCase` + `POST /api/jobs/backfill-emails?dryRun=`
(auth required, bot-token allowed; `dryRun` defaults `true`): scans stored
jobs with null `contactEmail`, runs the current `extract(title, description)`
on each, and returns `{scanned, filled, stillNull}` — writing nothing when
dry-run, persisting only null→found transitions when `false`. Idempotent and
rerunnable; existing emails are never overwritten.

This is the DB-level before/after the fixture recall cannot give: dry-run
with the OLD extractor, then dry-run with the NEW one — both counts recorded
in §6. The delta answers "are there really more emails than we have now".

## 6. Acceptance criteria (from #75)

- [ ] Recall/precision/false-positive measured BEFORE (committed fixtures +
      measurement test, no behavior change) and AFTER, both numbers recorded
      here: baseline recall ___, precision ___, after recall ___, precision ___.
- [ ] Preference rule documented: `mailto:` > hiring-local-part >
      generic-first; title before description.
- [ ] Unit tests green (fixture-driven + targeted matrices for new
      obfuscations and ranking; plain JUnit 5, no Spring, no network).
- [ ] Zero invented addresses: every persisted email is a literal text match
      (property-style test: shuffled/decoy texts never yield an address not
      present in input).
- [ ] Backfill dry-run counts recorded here (old extractor: scanned ___,
      filled ___; new extractor: scanned ___, filled ___).
- [ ] Backfill apply fills only nulls, never overwrites, rerunnable green;
      unit tests with mocked repo (dry-run writes nothing).

## 7. Out of scope

#74 (company domains), #79 (per-user keywords), new providers, cutoff/
prompt/scorer changes, email sending.
