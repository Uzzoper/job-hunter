# Spec: Preferences → Scoring Consumption (last mile)

> **Issue:** analyzed ranked lists ignore user work preferences — an onsite-SP role
> can outscore a remote fit today.
> **Layer:** `application` (`AiAnalysisService`, `EmailGenerationService`) ·
> `docs/specs/prompts.md`
> **Implementation files:**
> - `application/service/JobPreferenceScorer.java` (deterministic score modifier)
> - `application/service/PreferencesPromptFormatter.java` (prompt context rendering)
> - `application/service/AiAnalysisService.java` (prompt injection + score adjustment)
> - `application/service/EmailGenerationService.java` (prompt injection)
> - `docs/specs/prompts.md` (prompt documentation — updated first, per repo rule)
> **Tests:**
> - `unit/application/JobPreferenceScorerTest.java` (deterministic formula)
> - `unit/application/AiAnalysisServiceTest.java` (new `PreferencesScoringTests` nested class)
> - `unit/application/EmailGenerationServiceTest.java` (new `PreferencesPromptTests` nested class)

---

## Goal

Make user work preferences **actually consumed** by the analysis and email
generation flows. Today `AiAnalysisService.buildPrompt` and
`EmailGenerationService.buildPrompt` use only `profile.skills()` /
`profile.resumeText()`; the `UserPreferences` object persisted since #50 is
never read downstream, so an onsite-SP job advertised as "presencial em São
Paulo" can score above a fully-remote fit for a user who prefers remote.

This spec delivers the missing last mile:

1. **Prompt consumption** — inject the preference context (`WorkPreference`
   variant + cities, `salaryFloor`, `excludedCompanies`) into BOTH prompts
   (analysis + generation).
2. **Deterministic scoring** — add a small, documented, deterministic
   work-model fit modifier in the analysis scoring path, so a preference
   conflict guarantees a lower persisted `matchScore` even if the AI ignores
   the injected context. The ranked list (`GET /api/jobs`, sorted by
   `matchScore` descending) then automatically reflects fit.
3. **Backward compatibility** — when the profile carries no preferences
   (`preferences` is `null` or semantically blank), prompts and scores are
   **byte-identical to today's behavior**.

No migration (read-only consumption of the existing `work_preference` /
`salary_floor` / `excluded_companies` columns). No new bot-writable endpoints.
No auto-send gate changes (blast-radius invariant from `user-preferences.md`
is preserved: this feature only affects ranking/filtering/draft generation).

---

## Context

- `UserProfile.preferences` is a nullable `UserPreferences` value object
  (`workPreference` — sealed `Remote | Hybrid | Onsite` sum type; `salaryFloor` —
  BRL/month; `excludedCompanies`). It exists yet nothing downstream reads it.
- `AiAnalysisService.analyze` persists `matchScore` via
  `JobAnalysisRepository.save`; `FetchJobsService.findAllWithDraftStatus` sorts
  ranked lists (and `minScore` filters) by that persisted score. Adjusting the
  score **at analysis time** flows into every ranked list with zero list-side
  changes.
- The previous attempt at this feature left **no recoverable state** (empty
  feature branch, no stashes, no untracked files) — this spec is written from
  the clean branch state.
- Existing analyses keep their stored score until re-analyzed (no backfill —
  documented decision, see Out of scope).

---

## Deterministic scoring rules

Applied in `AiAnalysisService.analyze` AFTER JSON parsing (raw AI score),
BEFORE persistence. The final stored score is
`clamp(0, 100, rawScore + workModelModifier)` unless the company is excluded.

### Terms detection (description text, lowercased)

| Signal | Terms (substring match on lowercased description) |
|---|---|
| Remote mentioned `R` | `remoto`, `remota`, `remotos`, `remotas`, `remote`, `remotamente`, `home office`, `homeoffice`, `work from home`, `fully remote`, `anywhere`, `qualquer lugar` |
| Onsite mentioned `S` | `presencial`, `onsite`, `on-site` |
| Hybrid mentioned `H` | `hibrido`, `híbrido`, `hybrid` |
| Preferred city mentioned `C` | any user city (Hybrid/Onsite) as a lowercased substring |
| Remote negation guard | `não é remoto`, `nao e remoto`, `não é home office`, `nao e home office`, `not a remote position`, `not remote` → clears `R` |

`R` is only true when a remote term matches AND no negation phrase matches
("vaga 100% presencial — **não é remoto**" must be detected as onsite, not
remote). `S`/`H` have no negation guard (conservative: no penalty on unknown,
penalty only on explicit contradiction).

### Work-model fit modifier formula

| User preference | Condition | Modifier |
|---|---|---|
| `Remote` | `S && !R` | **−15** (explicit onsite contradicting remote preference) |
| `Remote` | `H && !R && !S` | **−8** (hybrid requires some presence) |
| `Remote` | otherwise | 0 (including fully-remote ads and unknown) |
| `Hybrid(cities)` | `R && !H && !S && !C` | **−8** (pure-remote job, no preferred city mentioned — presence expected) |
| `Hybrid(cities)` | otherwise | 0 (city match, onsite/hybrid stated, or unknown → no penalty) |
| `Onsite(cities)` | `R && !H && !S && !C` | **−15** (explicit remote contradicting onsite preference) |
| `Onsite(cities)` | `R && !H && !S && C`  | **−8** (remote job whose office sits in a preferred city) |
| `Onsite(cities)` | otherwise | 0 |

Design rationale (documented): only **explicit contradictions** are penalized;
silent/unknown descriptions never get penalized (no invented negativity). The
−15/−8 tiering mirrors the AI prompt score bands (majority-fit ≈ 60+ / partial
≈ 40–59): a full contradiction (−15) pulls a high raw score below the template
threshold in most cases, and the AI's own preference-aware raw score does the
heavy lifting. The deterministic modifier is the guaranteed floor.

### Excluded-company cap

| Condition | Final score |
|---|---|
| `job.company()` matches any excluded company (trimmed, case-insensitive) | `min(rawScore, 15)` — hard skip, regardless of skills/modifier |

The 15 cap sits below the `min-match-score` template threshold (default 60) and
below the "majority fit" AI band, so an excluded company never reaches the
template/auto-suggest path and its email draft goes through the AI path with
the preference context (where rule 12 drives `NO_APPLY`).

### `salaryFloor` (prompt-only)

The `Job` domain model has no salary column, so no deterministic floor
comparison is possible. `salaryFloor` is injected into prompts only, where the
AI treats "below floor" as a negative signal (score + generation).

### Identity guarantee

- `preferences == null` OR semantically blank
  (`workPreference == null && salaryFloor == null && excludedCompanies
  isEmpty`) → `adjust` returns the raw score unchanged and the prompt is
  byte-identical to today.
- Clamping: `min(max(rawScore + modifier, 0), 100)` applied after adjustment.

---

## Generation rules (email prompt)

The email-generation prompt gains the same preference block plus an explicit
rejection rule:

> **Rule 12.** If the job clearly conflicts with an explicit preference above
> (excluded company, incompatible work model, or salary below the floor), do
> NOT write an email — respond with exactly one line:
> `NO_APPLY: [one-line reason in English]`.

Reuses the existing `NO_APPLY:` refusal contract (`email-no-apply-refusal.md`):
REJECTED draft + best-effort bot-memory write-back.

---

## Prompt changes (documented in `prompts.md`)

Both prompts gain, **only when preferences are set**, a section rendered by
`PreferencesPromptFormatter`:

```
Candidate preferences (authoritative):
- Work model: Remote — fully remote / anywhere
             | Hybrid — office in: Curitiba, São Paulo
             | Onsite — office in: Curitiba
             | Not set
- Salary floor: R$ 5000 per month (BRL) | Not set
- Excluded companies (hard skip): Acme Corp, Globex | None
```

- Prompt 1 (analysis) appends the block + scoring directive: lower `matchScore`
  on work-model conflict / below-floor salary; excluded companies score 0–10.
- Prompt 2 (generation) appends the block + Rule 12 (above).
- Absent preferences → block omitted entirely (byte-identical prompts).

---

## Interface contract

No port changes. `PreferencesPromptFormatter` / `JobPreferenceScorer` are plain
stateless application-layer classes (no Spring), following the `TemplateEmailService`
precedent of a framework-free helper.

```java
// application/service/JobPreferenceScorer.java
public final class JobPreferenceScorer {
    public static int adjust(int rawScore, Job job, UserPreferences preferences);
    // null or blank preferences → rawScore unchanged; excluded company → min(rawScore, 15);
    // otherwise clamp(rawScore + workModelModifier, 0, 100)
}

// application/service/PreferencesPromptFormatter.java
public final class PreferencesPromptFormatter {
    public static boolean hasContent(UserPreferences preferences);
    public static String block(UserPreferences preferences); // "" when no content
}
```

---

## Acceptance criteria

| # | Criterion | Test method |
|---|-----------|-------------|
| 1 | Remote preference + onsite ad ("presencial", not "não é remoto") → −15 | `remote_whenDescriptionOnsite_shouldSubtractFullPenalty`, service-level `analyze_whenRemotePreferenceAndOnsiteJob_shouldPersistAdjustedScore` |
| 2 | Remote preference + hybrid ad → −8 | `remote_whenDescriptionHybrid_shouldSubtractMildPenalty` |
| 3 | Remote preference + remote ad → 0 | `remote_whenDescriptionRemote_shouldNotPenalize` |
| 4 | "100% presencial — não é remoto" still detected as onsite (negation guard) | `remote_whenDescriptionNegatesRemote_shouldStillDetectOnsite` |
| 5 | Silent description → 0 (never invent negativity) | `remote_whenDescriptionSilent_shouldNotPenalize` |
| 6 | Hybrid preference + pure-remote ad without preferred city → −8 | `hybrid_whenDescriptionRemoteAndCityUnknown_shouldSubtractMildPenalty` |
| 7 | Hybrid preference + city mentioned → 0 | `hybrid_whenDescriptionMentionsPreferredCity_shouldNotPenalize` |
| 8 | Onsite preference + remote ad, no city → −15 | `onsite_whenDescriptionRemoteAndCityMissing_shouldSubtractFullPenalty` |
| 9 | Onsite preference + remote ad with preferred city → −8 | `onsite_whenDescriptionRemoteButCityMatch_shouldSubtractMildPenalty` |
| 10 | Excluded company (case-insensitive) caps score at 15 | `excludedCompany_whenCompanyMatches_shouldCapScore` (also service-level) |
| 11 | No preferences / blank preferences → raw score unchanged | `adjust_whenNoPreferences_shouldReturnRawScore`, `adjust_whenEmptyPreferences_shouldReturnRawScore` |
| 12 | Only `salaryFloor` set → score unchanged (prompt-only signal) | `adjust_whenOnlySalaryFloor_shouldNotChangeScore` |
| 13 | Clamping at both bounds | `adjust_whenScoreWouldExceedBounds_shouldClamp` |
| 14 | Analysis prompt includes preference block when set | `analyze_whenPreferencesSet_shouldIncludeThemInPrompt` |
| 15 | Analysis prompt unchanged (no block) when preferences absent | `analyze_whenNoPreferences_shouldNotIncludePreferencesBlock` |
| 16 | Generation prompt includes preference block + rule 12 when set | `generate_whenPreferencesSet_shouldIncludePreferencesInPrompt` |
| 17 | Generation prompt unchanged when preferences absent | `generate_whenNoPreferences_shouldNotIncludePreferencesBlock` |
| 18 | Full suite GREEN (no regressions) | `./mvnw test` |

---

## Out of scope

- **Re-ranking existing analyses**: already-persisted scores stay until the job
  is re-analyzed (no migration/backfill — consistent with read-only consumption).
- **Salary determinism**: no salary field on `Job` → prompt-only signal.
- **Scraper-side filtering** by preferences (future preference-aware fetching).
- **Auto-send/eligibility gates**: blast-radius invariant preserved —
  `AutoSendEligibilityService` / `AutoSendScheduler` still read only `matchScore`
  + `EmailStatus`.
- **New endpoints / DTOs**: none; this is consumption of existing data only.