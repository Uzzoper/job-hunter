# Spec: Match quality package — truncation, template inversion, seniority-in-body, soft stack signal

> **Issue:** #77 · calibration evidence: #78 (owner-rated sample n=50, verdict closed)
> **Layer:** `application` (`AiAnalysisService`, `EmailGenerationService`, `JobPreferenceScorer`) · `config` (`AppConfig`, `application.yaml`) · `docs/specs/prompts.md`
> **Implementation files:**
> - `application/service/AiAnalysisService.java` (configurable truncation limits, synced Prompt 1 inline bands)
> - `application/service/EmailGenerationService.java` (configurable resume limit, inverted branch selection)
> - `application/service/JobPreferenceScorer.java` (seniority-in-body, soft stack signal, extended signature)
> - `infrastructure/config/AppConfig.java` (`@Value` wiring of the new limits)
> - `src/main/resources/application.yaml` (new `ai.analysis.*` / `email.*` keys)
> - `docs/specs/prompts.md` (Prompt 1 v4.1 — updated first, per repo rule)
> **Tests:**
> - `unit/application/JobPreferenceScorerTest.java` (plain JUnit — both new penalties + identity-guarantee extension)
> - `unit/application/AiAnalysisServiceTest.java` (ArgumentCaptor: truncation tail survival + stack path)
> - `unit/application/EmailGenerationServiceTest.java` (`TemplateBranchTests` inversion + resume-limit truncation)

---

## Context

Live apply-batch review produced four distinct quality defects (issue #77):

1. **Truncation** — analysis cuts the resume at 1,500 chars and the description at
   1,000 chars, and the email service cuts the resume at 1,000, while the Hermes
   gateway comfortably accepts 8,000. Stack/requirements sections ("Requisitos",
   "5 anos de experiência") routinely live past the old cut — the AI never sees
   the very text that determines fit.
2. **Template/AI inversion** — `EmailGenerationService` sends jobs with
   `matchScore >= 60` through the generic template and reserves AI-personalized
   emails for `matchScore < 60`. High-fit jobs get the worst treatment.
3. **Seniority blind spot** — the seniority signal catches titles ("Pleno") but
   not "5 anos de experiência" stated in the job body.
4. **Invisible stack mismatch** — when the advertised stack (PHP/Angular/
   CodeIgniter) is entirely outside the candidate's, the score does not move. The
   owner works with unfamiliar stacks, so stack fit must be a **soft ordering
   penalty, never a blocker** — same for degree (the owner is a 2027 graduate, so
   there is **no hard degree gate**).

Per issue #78's calibration verdict the cutoffs are **kept** (70 auto-apply, 60–69
human review), and a **prompt adjustment is NEEDED**: seniority and eligibility
(degree/stack) belong in the analysis prompt + scorer. This spec executes that
verdict.

---

## Cutoffs (from #78, authoritative)

Owner-rated sample n=50 (rule: BOM except pleno/PL-titled, 106 RUIM-fonte):
BOM=30, pleno-titled=19, RUIM-fonte=1. **Precision-at-70 = 9/15 = 60%**.
**60–69 band hides 12/20 BOM (60%)** — appliable jobs sit there and must surface
in human review. <60 band: 9/15 BOM (60%) including 132/176.

Verdict: **do NOT lower the cutoff.** The 70 gate stays for bot auto-apply; the
60–69 band becomes the human review queue; quality work happens in scoring and
prompt, not in threshold movement.

### Cutoff ownership (which layer owns which number)

| Number | Meaning | Enforced by |
|---|---|---|
| **60** (`email.standard-template.min-match-score`, default 60 — `AppConfig.java:354`) | Email-generation **branch selector only** (post-inversion: `>= 60` → AI personalized path, `< 60` → template). It is NOT an apply gate. | **Java** — `EmailGenerationService` |
| **60–69** | Human review queue: these jobs are too good to auto-skip, so the owner reviews them before any bot action. | Process — the review-gate UX, not a code constant |
| **70** | Bot auto-apply floor. | **Bot-side only** — `skills/analyzer/SKILL.md` `min_score` parameter (e.g. 70), invoked by the `jobhunter-bot` profile. **Java does not enforce the 70 anywhere**; the backend must not add a deterministic 70 gate. |
| — (none) | Auto-send eligibility has **NO score gate** — every eligible draft is sent in priority order (`auto-send-scheduler.md`). | Java — `AutoSendEligibilityService` (unchanged) |

---

## 1. Truncation — raise and configure

### Current state (silent cuts)

| Site | Constant | Value | Cuts? |
|---|---|---|---|
| `AiAnalysisService` | `MAX_RESUME_CHARS` | 1,500 | yes — `...` suffix, **no log** |
| `AiAnalysisService` | `MAX_DESCRIPTION_CHARS` | 1,000 | yes — `...` suffix, **no log** |
| `EmailGenerationService` | `MAX_RESUME_CHARS` | 1,000 | yes — inline `substring`, **no log** |

### Decision

- Raise all three effective limits to **8,000** and make them configurable:
  - `ai.analysis.max-resume-chars` (default `8000`) → `AiAnalysisService` resume excerpt
  - `ai.analysis.max-description-chars` (default `8000`) → `AiAnalysisService` description excerpt
  - `email.max-resume-chars` (default `8000`) → `EmailGenerationService` resume excerpt
- Follow the **existing pattern** at `application.yaml:39-43`
  (`ai.resume-extraction.max-chars: 8000` / `ai.resume-tailoring.max-chars: 8000`),
  wired via `@Value("${ai.analysis.max-resume-chars:8000}")` in `AppConfig` exactly
  like `AppConfig.java:393`/`:452` do for extraction/tailoring.
- The class constants `MAX_RESUME_CHARS` / `MAX_DESCRIPTION_CHARS` become
  constructor-injected `int`s:
  - `AiAnalysisService(AiPort, JobAnalysisRepository, UserProfileRepository, JobRepository, int maxResumeChars, int maxDescriptionChars)`
  - `EmailGenerationService(..., int minMatchScore, int maxResumeChars)` — one bool-free append to the existing constructor.
- Truncation helper must **`log.warn` on cut**, adopting the
  `ResumeUploadService.java:266-268` pattern verbatim:

```java
if (text.length() > maxChars) {
    log.warn("{} is {} chars, truncating to {} for AI prompt", label, text.length(), maxChars);
    text = text.substring(0, maxChars) + "...";
}
```

- Truncation remains a hard cut (no smart extraction in this iteration). The
  raised default means the common case (descriptions up to 8,000 chars) stops
  losing the stack/requirements tail; only pathological listings still truncate,
  and those now log a warning.

### Before/after sample

- **Before**: a 1,340-char Gupy description whose `Requisitos` section (with the
  stack tokens and "5 anos de experiência") starts at char ~1,050 → cut at 1,000,
  the deciding text is absent from the prompt; the score drifts toward generic fit.
- **After**: default 8,000 → the whole listing (and thus the requirements section)
  survives in the prompt; the deterministic seniority/stack signals (sections 3–4)
  read the same content regardless, because `JobPreferenceScorer` always runs on
  the **full domain text**, independent of prompt truncation.

**Acceptance test:** a description between 1,000 and 8,000 chars must keep its tail
(`Requisitos`, the specific stack token, `5 anos de experiência`) in the captured
prompt — asserted via `ArgumentCaptor<String>` on the mocked `AiPort`.

---

## 2. Template/AI inversion

### Current state (`EmailGenerationService.java:102-104`)

```java
if (analysis.matchScore() >= minMatchScore) {
    return generateFromTemplate(job, user, profile, userId);
}
```

### Decision — invert the branch

`matchScore >= minMatchScore` → **PERSONALIZED AI path**; `else` (low band) →
**template**. The template is now the **LOW-score fallback** (`minMatchScore`
stays 60 via `email.standard-template.min-match-score`, `AppConfig.java:354`).

```java
if (analysis.matchScore() < minMatchScore) {
    return generateFromTemplate(job, user, profile, userId);
}
// matchScore >= minMatchScore → AI personalized path (existing AI block)
```

### Documented consequences

(a) **AI refusal on high band is intentional.** The AI path can now return
`NO_APPLY:` for a `>= 60` job → the draft is persisted `REJECTED` and is visible
in review as "declined by AI". That is the point: high-fit gets personalized
treatment, and the refusal contract (`email-no-apply-refusal.md`) governs it
exactly as it governs the low band today.

(b) **`email-no-apply-refusal.md:60` must be reworded.** The sentence "the
template branch (`matchScore >= minMatchScore`) always produces `PENDING`" is
inverted: the template branch now serves the **low** band and still always
produces `PENDING` when it runs (subject to the pre-existing guard that a
template body never starts with `NO_APPLY:`).

(c) **Idempotency guards unchanged and still first.** The two `SENT`
short-circuits (`EmailGenerationService.java:87-100`) run before branch selection
and are untouched — an already-sent pair never regenerates, regardless of the new
branch semantics.

(d) **More AI calls on the high band.** Every `>= 60` job now costs one AI
generation instead of a free template render. Expected and accepted — it is the
direct consequence of giving the best treatment to high-fit jobs. The template
path (cost ≈ 0) now covers the low band where it acts as a deterministic,
credential-preserving fallback.

---

## 3. Seniority-in-body signal

`JobPreferenceScorer.seniorityModifier` currently detects the mismatch on the
**title only** (`JobPreferenceScorer.java:49-52` — word-boundary `pleno` / `pl` /
`mid-level`). Extend it to also detect **years-of-experience requirements in
`job.description()`**.

### Detection contract (body signal)

- Anchor on **experience context**, PT + EN, case-insensitive:
  `anos de experiência` | `anos de experiencia` | `years of experience`.
- A mismatch fires when a **bare integer between 3 and 30** appears within a
  short window (±6 tokens) of that marker — either order ("5 anos de
  experiência", "years of experience required: 5").
- Numbers outside 3..30 are ignored ("1 ano de experiência", "até 2 anos" are
  junior-compatible; "35 anos de experiência" is a senior/executive ad, out of the
  junior-seeker's normal band for this signal). `2` is the junior boundary and
  does **not** fire.
- **Never** treat a bare "pleno"/"PL" in free-form prose as the signal — the
  body signal is experience-number-scoped only. The word patterns stay title-only.
- Never blocking; ordering only.

### Applied at most once

The penalty composes as **0 or −10 total**: `title-hit OR body-hit` →
`-SENIORITY_MISMATCH_PENALTY`; a job whose title says "Pleno" AND whose body says
"5 anos de experiência" still loses **10**, never 20.

```java
private static int seniorityModifier(Job job) {
    boolean mismatch = titleSignal(job.title()) || bodyExperienceSignal(job.description());
    return mismatch ? -SENIORITY_MISMATCH_PENALTY : 0;
}
```

### Gate (unchanged)

Fires **only when `preferences.workPreference() != null`**, exactly as today
(`JobPreferenceScorer.java:74-76`). Salary-only and preference-less profiles stay
**byte-identical** — the identity guarantee at `preferences-scoring.md:142-149`
(PR #80 review P0-3) is preserved by construction.

---

## 4. Soft stack signal (deterministic, scorer-side)

### New constant

`STACK_MISMATCH_PENALTY = 10` (same magnitude as seniority, additive in the clamp).

### Exact signature change

```java
// BEFORE (single call site: AiAnalysisService.java:62)
public static int adjust(int rawScore, Job job, UserPreferences preferences)

// AFTER
public static int adjust(int rawScore, Job job, UserPreferences preferences,
                         List<String> profileSkills)
```

**Decision (documented):** pass `profile.skills()` — the plain
`List<String>` — not the whole `Profile`. Rationale: (1) the scorer needs only the
skill token list; (2) tests build the list directly (the scorer is a pure static
helper, no Spring); (3) keeps the interface minimal and avoids importing a heavier
domain object. The call site becomes
`JobPreferenceScorer.adjust(parsed.matchScore(), job, profile.preferences(), profile.skills())`.
`UserProfile.skills()` is a non-null `List<String>` — no null-guard needed at the
call site.

### Condition

Penalty −10 applies **only when**:

1. profile skills are **non-empty after trim/blank filtering**, **and**
2. **NONE** of the normalized profile skill tokens appears in the normalized
   `title + " " + description` haystack.

Normalization: each skill and both title/description are lowercased and trimmed;
a skill hits when its normalized string is a substring of the haystack — the same
`contains()` idiom as the existing city detection (`mentionsAnyCity`,
`JobPreferenceScorer.java:158-160`). "Java" hits `"java"`, "Spring Boot" hits
`"spring boot"`.

### Gate / identity guarantee

- **Skills empty/blank → no penalty** — skill-less profiles stay byte-identical
  (identity-guarantee extension of `preferences-scoring.md:142-149`).
- The stack signal is **independent of `workPreference`**: it fires for any
  profile carrying skills, preferences or not. This is a deliberate, documented
  new signal — it does not widen the *existing* byte-identical promise, which
  covers preference-less/skill-less profiles only.
- Never blocking, ordering only. `matchScore` is a ranking input, not a gate.

### Explicit non-goals (issue rule)

NO new AI response fields, NO Flyway migration, NO eliminatory field, NO
deterministic eligibility drop. Degree/stack eligibility stays soft — ordering
signals only.

---

## 5. Formula update

The final stored score becomes:

```
matchScore = clamp(0, 100, rawScore + workModelModifier + seniorityModifier + stackModifier)
```

Exception (unchanged): an excluded company caps at `min(rawScore, 15)` regardless
of any modifier. This supersedes the formula at `preferences-scoring.md:72`
(`clamp(0, 100, rawScore + workModelModifier + seniorityModifier)`) — that spec
must be updated, plus its seniority section (`:111-123`), identity section
(`:142-149`), and interface contract (`:195-208`) to match this spec.

---

## 6. Prompt adjustment (Prompt 1 → v4.1)

Per #78's verdict (**NEEDED**) the analysis prompt must reflect seniority and
eligibility (degree/stack) as score-down factors.

### Version history row (`prompts.md:363-373` table)

| Version | Date | Change |
|---|---|---|
| v4.1 | 2026-09 | Prompt 1 band descriptions updated: `matchScore` must reflect years-of-experience mismatch and required-degree mismatch as **score-down factors**; response format unchanged (still 5 fields: `matchScore`, `matchedSkills`, `missingSkills`, `companyTone`, `summary`) — see `match-quality.md` (#77) |

### Band semantics (updated)

Prompt 1's `matchScore criteria` bands keep the same thresholds but gain explicit
score-down drivers: a job requiring years of experience above the candidate's
junior level, or a required degree the candidate does not hold (owner is a 2027
graduate), must **lower** the score within the applicable band. Response format
**STAYS** the 5 fields — no schema change.

### Reconciliation statement (which text wins)

`prompts.md` Prompt 1 is the canonical, richer text and **wins**. The inline code
prompt at `AiAnalysisService.java:83-98` currently carries a degraded version
(`Score: 80-100=todos requisitos, 50-79=maioria, <50=poucos matches`). As part of
implementation the code prompt must be rewritten to the v4.1 band semantics,
adapted to code form. `analyze-job.md:54` ("implementation may inline equivalent
instructions") is the escape hatch: inlining is allowed as long as the semantics
match `prompts.md` Prompt 1 v4.1 — it is not a license to keep the degraded bands.

---

## 7. Degree decision (resolves #77 vs #78)

The required-degree mismatch is **informational in the prompt only** (score-down
factor for the AI band choice) and carries **zero deterministic gate**: no scorer
penalty, no eligibility drop. This settles the tension between #77 (soft) and
#78's "requiresDegree-style pre-filter": the pre-filter is **not** built. Owner is
a 2027 graduate; a deterministic degree block would exclude him from roles he is
genuinely applying for. The prompt alone informs the AI; the human review queue
(60–69) is where degree-edge cases get seen.

---

## 8. Cross-ref spec updates (after GREEN)

- `generate-email.md` — Scenario 1 flips to `matchScore >= threshold`; Scenario 6
  becomes the low-score fallback; business rule at `:59` ("When `matchScore >=
  minMatchScore`, a fixed template replaces the AI call") is inverted; `:12`
  Scenario 1 header text.
- `template-email.md` — Context at `:12` ("do not need AI-personalized emails")
  → template is the low-score fallback; `:102` Scenario 3 (`matchScore <
  threshold` → AI prompt reference example) still holds but the labeling flips;
  `:6` "Depends on" wording.
- `auto-send-scheduler.md` — `:72` ("high-score → template, low-score → AI") is
  inverted. The **no-score-gate invariant at `:140` stays true**: score still
  determines email type only, never whether to send.
- `email-no-apply-refusal.md` — `:60` reworded: the template branch serves the
  low band and still always produces `PENDING` when it runs (subject to the
  pre-existing template-body `NO_APPLY:` guard in `generateFromTemplate`).
- `preferences-scoring.md` — formula at `:72` gains `stackModifier`; seniority
  section `:111-123` gains the body signal (at most once, same gate); identity
  section `:142-149` gains the blank-skills extension; interface contract
  `:195-208` gains the `List<String> profileSkills` parameter.
- `prompts.md` — v4.1 row in the version-history table and Prompt 1 band text
  (canonical — code follows it).
- `analyze-job.md` — no change required; `:54` already permits inline equivalence
  with the canonical `prompts.md` Prompt 1.

---

## 9. TDD plan (test-first landing spots per layer)

| Layer | Test file | New/updated tests |
|---|---|---|
| `application` (scorer) | `JobPreferenceScorerTest` (plain JUnit, no mocks) | Body signal: `seniority_whenDescriptionMentionsFiveYearsExperience_shouldSubtractPenalty` (PT + EN), `seniority_whenTitleAndBodyBothMismatch_shouldApplyPenaltyOnce` (−10, never −20), `seniority_whenDescriptionMentionsBarePleno_shouldNotPenalize` (prose "pleno" ≠ signal), range edges: `seniority_whenDescriptionTwoYears_shouldNotPenalize`, `seniority_whenOnlySalaryFloorAndBodyYearsExperience_shouldKeepScore` (identity). Stack: `stack_whenNoSkillOverlap_shouldSubtractPenalty`, `stack_whenTitleMentionsSkill_shouldKeepScore`, `stack_whenDescriptionMentionsSkill_shouldKeepScore`, `stack_whenSkillsEmpty_shouldKeepScore` (identity extension), `stack_whenAllSignalsCombine_shouldClamp`. Existing helper call sites gain the skills argument. |
| `application` (analysis) | `AiAnalysisServiceTest` (Mockito + `ArgumentCaptor`) | `analyze_whenDescriptionBetweenOldAndNewCut_shouldKeepTailInPrompt` (asserts `Requisitos` + stack token + "5 anos de experiência" in the captured prompt), `analyze_whenTextExceedsLimit_shouldTruncateAndWarn`, `PreferencesScoringTests` gains stack-path coverage (adjust receives `profile.skills()`). |
| `application` (email) | `EmailGenerationServiceTest` (`TemplateBranchTests` inversion) | `generate_whenHighScore_shouldCallAiNotTemplate` (`>= 60` → `AiPort.complete()` called, template builder untouched), `generate_whenLowScore_shouldUseTemplateWithoutAi` (`< 60` → template, `AiPort` never called, `PENDING`), plus `generate_whenResumeExceedsLimit_shouldTruncateAndWarn` (limit injected). |

Unit tests never start a Spring context (AGENTS.md rule).

---

## 10. Acceptance criteria (mirrors issue #77)

| # | Criterion | Checkable |
|---|-----------|-----------|
| 1 | Truncation limits raised and configurable: `ai.analysis.max-resume-chars`, `ai.analysis.max-description-chars`, `email.max-resume-chars`, all defaulting to 8000 | `application.yaml` keys present; `@Value` defaults; before/after sample in this spec |
| 2 | Truncation logs a warning on cut (ResumeUploadService pattern) | captures in `AiAnalysisServiceTest` / `EmailGenerationServiceTest` |
| 3 | Description > 1,000 chars keeps its stack/requirements tail in the analysis prompt | `analyze_whenDescriptionBetweenOldAndNewCut_shouldKeepTailInPrompt` (ArgumentCaptor) |
| 4 | Template/AI inversion: `>= 60` → AI personalized, `< 60` → template | `TemplateBranchTests` after inversion |
| 5 | AI refusal on high band is intentional and reviewable (`REJECTED`, `email-no-apply-refusal.md` rewording) | regression on `NoApplyRefusalTests`; review UI shows the refusal |
| 6 | Seniority-in-body: 3..30 years in experience context lowers score by 10, at most once per adjust | scorer unit tests (PT + EN, single-fire, bounds) |
| 7 | Seniority gate unchanged: only with explicit `workPreference`; salary-only stays byte-identical | `seniority_whenOnlySalaryFloor_*` family |
| 8 | Soft stack signal: no overlap (skills non-empty) → −10; any overlap → 0 | scorer unit tests |
| 9 | Skills empty/blank → no penalty (identity extension) | `stack_whenSkillsEmpty_shouldKeepScore` |
| 10 | No AI response schema change, no Flyway migration, no eliminatory field | `prompts.md` v4.1 keeps the 5-field format; diff contains no migration |
| 11 | Prompt 1 v4.1 bands documented in `prompts.md`; code prompt brought in sync | version-history row; `AiAnalysisService` inline bands match `prompts.md` |
| 12 | Calibration cutoffs applied: Java enforces 60 (branch selection) only; 70 stays bot-side; auto-send stays gate-less | `skills/analyzer/SKILL.md` untouched; no new Java 70 constant |
| 13 | Cross-ref specs updated | `generate-email.md`, `template-email.md`, `auto-send-scheduler.md`, `email-no-apply-refusal.md`, `preferences-scoring.md`, `prompts.md` |
| 14 | Full suite GREEN (no regressions) | `./mvnw test` |

---

## 11. Out of scope

- **Per-user seniority preferences** — #79, separate issue; this spec adds a fixed
  years-of-experience signal only.
- **ATS source handling** — #76, separate issue.
- **No AI schema change** — response format stays `matchScore`,
  `matchedSkills`, `missingSkills`, `companyTone`, `summary`.
- **No cutoff lowering** — 70 is the bot-applicable floor (bot-side), 60–69 is the
  human review queue, nothing below auto-applies by design.
- **No deterministic 70 gate in Java** — backend changes selection logic only.
- **No deterministic degree gate** — degree is informational in the prompt only
  (2027 graduate; see Degree decision).
- **No Flyway migration** — no schema/DB change anywhere.
- **No smart-extraction** — truncation stays a hard cut with a raised default;
  before/after sample documents the common-case win.
- **No re-ranking of existing analyses** — stored scores stay until re-analyzed
  (consistent with `preferences-scoring.md`).

---

## 12. Agent prompt (OpenCode)

```
Read the spec at docs/specs/match-quality.md plus docs/specs/prompts.md
(Prompt 1, version history), docs/specs/preferences-scoring.md,
docs/specs/generate-email.md, and docs/specs/email-no-apply-refusal.md.

Step 1 — write the RED tests only (no production code):
- JobPreferenceScorerTest: seniority-in-body suite + stack suite +
  identity-extension tests (update the test helpers for the new
  adjust(rawScore, job, preferences, skills) signature).
- AiAnalysisServiceTest: truncation-tail ArgumentCaptor tests +
  stack-path coverage in PreferencesScoringTests.
- EmailGenerationServiceTest: TemplateBranchTests inversion +
  resume-limit truncation test.
Show the tests and ask "do you want to adjust before implementing?".

Step 2 — after confirmation, implement GREEN:
- application.yaml + AppConfig @Value wiring for the three new limits.
- AiAnalysisService / EmailGenerationService constructor changes,
  log.warn truncation helpers, inverted email branch.
- JobPreferenceScorer: body experience signal, stack signal, new
  signature; update the AiAnalysisService call site.
- prompts.md v4.1 row + Prompt 1 band text; sync the AiAnalysisService
  inline prompt to match.

Step 3 — after GREEN, update the cross-ref specs listed in section 8
and confirm which tests are GREEN.
```