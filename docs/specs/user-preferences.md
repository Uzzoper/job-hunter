# Spec: User Work Preferences Model with Anti-Hallucination Guards

> **Issue:** #50
> **Layer:** `domain` (model) · `application` (service merge) · `infrastructure` (persistence) · `web` (DTO exposure)
> **Implementation files:**
> - `domain/model/WorkModel.java` (enum)
> - `domain/model/UserPreferences.java` (value object with validation)
> - `domain/model/UserProfile.java` (extended with nullable preferences)
> - `infrastructure/persistence/UserProfileEntity.java` (new nullable columns)
> - `infrastructure/persistence/UserProfilePersistenceAdapter.java` (field mapping)
> - `application/service/BotMemorySyncService.java` (parse-lenient / validate-strict firewall)
> - `resources/db/migration/V6__add_user_preferences_columns.sql`
> - `web/dto/ProfileRequest.java` (optional preferences fields)
> - `web/dto/PreferencesRequest.java` (preferences request sub-object with bean validation)
> - `web/dto/ProfileResponse.java` (preferences exposure)
> - `web/dto/PreferencesResponse.java` (preferences response sub-object)
> - `web/controller/ProfileController.java` (pass-through + preserve-on-null via service)
> **Tests:**
> - `unit/domain/UserPreferencesTest.java` (adversarial validation)
> - `unit/application/BotMemorySyncPreferencesTest.java` (adversarial merge + parse firewall)

---

## Goal

Model user work-preference data (`workModel`, `salaryFloor`, `locations`,
`excludedCompanies`) as a domain value object with strict validation, fed into
the profile by the Hermes bot's memory-merge path and by the user via the API.
Bot-sourced values go through a **parse-lenient / validate-strict** firewall
that normalizes, exact-matches, and sanity-caps raw strings before they ever
touch persistence — eliminating hallucinated, ambiguous, or absurd values.

---

## Context

- **Existing `UserProfile`** holds contact fields, skills, tone, and projects.
  Preferences are a new nullable composition — no existing row has them.
- **Bot memory merge** (`BotMemorySyncService.mergeIntoProfile`) already follows
  fill-if-empty semantics: human-set values always win.
- **Auto-send** (`AutoSendEligibilityService` / `AutoSendScheduler`) operates on
  `matchScore` and `EmailStatus` only — it never reads preferences today. This
  blast-radius invariant is documented below and verified in code.
- **TUI / API** (`ProfileController PUT /api/profile`) allows the user to set
  preferences directly; these are authoritative and never overwritten by bot data.

---

## Domain model

### `WorkModel` enum (pure Java, zero Spring)

```java
public enum WorkModel {
    REMOTE, HYBRID, ONSITE
}
```

### `UserPreferences` record

```java
public record UserPreferences(
    WorkModel workModel,           // nullable — null when unset
    Integer salaryFloor,           // nullable — null when unset; must be > 0 if present
    List<String> locations,        // nullable → normalized to empty list; max 20 items, each max 100 chars
    List<String> excludedCompanies // nullable → normalized to empty list; max 50 items, each max 200 chars
) {
    // compact constructor: validation + normalization
}
```

**Validation rules (compact constructor):**
| Field | Rule | On violation |
|-------|------|-------------|
| `workModel` | nullable | null passes through |
| `salaryFloor` | nullable; if present, must be > 0 | `IllegalArgumentException` |
| `salaryFloor` | if present, must be ≤ 500,000 (sanity cap) | `IllegalArgumentException` |
| `locations` | nullable → `List.of()`; at most 20 items | excess silently trimmed |
| `locations` items | non-null, trimmed; max 100 chars each | `IllegalArgumentException` if any item exceeds |
| `excludedCompanies` | nullable → `List.of()`; at most 50 items | excess silently trimmed |
| `excludedCompanies` items | non-null, trimmed; max 200 chars each | `IllegalArgumentException` if any item exceeds |

**Constants:**
- `MAX_LOCATIONS = 20`
- `MAX_LOCATION_LENGTH = 100`
- `MAX_EXCLUDED_COMPANIES = 50`
- `MAX_COMPANY_NAME_LENGTH = 200`
- `MAX_SALARY_FLOOR = 500_000`

---

## UserProfile extension

`UserProfile` gains one nullable field **at the end of the record**:

```java
public record UserProfile(
    // ... existing fields ...
    UserPreferences preferences   // nullable — null = no preferences set
) {}
```

The compact constructor normalizes `null` preferences to `null` (no default
object — absence is explicit).

---

## Flyway V6 migration

```sql
ALTER TABLE user_profiles ADD COLUMN work_model VARCHAR(20);
ALTER TABLE user_profiles ADD COLUMN salary_floor INTEGER;
ALTER TABLE user_profiles ADD COLUMN locations TEXT;          -- JSON via StringListConverter
ALTER TABLE user_profiles ADD COLUMN excluded_companies TEXT; -- JSON via StringListConverter
```

All columns are **nullable**. Zero blast radius: existing rows retain `NULL` for
all four columns; no data migration required.

---

## Persistence mapping

`UserProfileEntity` gains four nullable fields with getter/setter pairs.

`UserProfilePersistenceAdapter`:
- `toEntity`: maps `preferences.workModel()?.name()`, `preferences.salaryFloor()`,
  `StringListConverter` for locations/excludedCompanies
- `toDomain`: reconstructs `UserPreferences` from entity fields; `null` columns → `null` preferences

---

## Parse-lenient / validate-strict firewall

### Location in the merge path

Raw bot memory values enter via `BotMemorySyncService.mergeIntoProfile`. Before
they touch `UserPreferences`, they pass through a normalization + validation
layer:

### WorkModel tokens (exact-match after normalization)

1. **Normalize**: lowercase + strip diacritics (NFD decomposition, strip combining marks)
2. **Exact-match** against known tokens:

| Normalized token | Result |
|------------------|--------|
| `remoto`, `remote` | `REMOTE` |
| `hibrido`, `hybrid`, `híbrido` | `HYBRID` |
| `presencial`, `onsite`, `on-site`, `on site` (normalized) | `ONSITE` |
| anything else | dropped with WARN log |

### SalaryFloor (digits-only with sanity cap)

1. **Extract**: strip non-digit characters from the raw string
2. **Parse**: `Integer.parseInt` on the cleaned string
3. **Sanity cap**: if > 500,000 → dropped with WARN log
4. **Positive check**: if ≤ 0 → dropped with WARN log
5. **Empty/blank/unparseable**: dropped silently (no value = no preference)

### Locations / ExcludedCompanies (list with bounds)

1. **Split**: comma-delimited raw value → trim each item
2. **Filter**: drop blank items, drop items exceeding max length
3. **Cap**: keep at most MAX_LOCATIONS (20) / MAX_EXCLUDED_COMPANIES (50) items
4. **Result**: empty list if no valid items remain → treated as "no preference"

### Audit trail

When a bot-sourced value is merged, the merge logs at INFO level:
```
"Merged preferences for user {}: workModel=old→new, salaryFloor=old→new (source: '<raw snippet>')"
```

---

## Fill-if-empty semantics (unchanged)

- **Human-set values (TUI/API)** always win — `isBlank` / `isEmpty` check on
  the existing value before bot data is applied.
- **Bot values** only fill fields that are currently null/empty.
- **Null preference object** on profile = all fields unfilled → bot data fills all.
- **Partial preference object** (e.g. only `workModel` set) = bot data fills
  remaining null fields only.

---

## Blast-radius invariant

**Invariant:** Bot-sourced `UserPreferences` data feeds ranking/filter/draft
generation only. It NEVER gates the auto-apply/auto-send path.

**Verification (code audit):**

| Component | Reads preferences? | Gate on auto-send? |
|-----------|--------------------|--------------------|
| `AutoSendEligibilityService` | No | N/A — only reads `EmailStatus` + `matchScore` |
| `AutoSendScheduler` | No | N/A — delegates to eligibility + `SendEmailUseCase` |
| `SendEmailUseCase` / `EmailSendingService` | No | N/A — sends an already-approved/selected draft |
| `JobListingService` (future ranking) | Would read | No auto-send interaction |
| `BotMemorySyncService` | Writes only | No auto-send interaction |

**Conclusion:** No existing code path violates the invariant. Preferences are
purely informational for filtering/ranking UI; the auto-send pipeline remains
behind explicit human confirmation (approve → send).

---

## Narrow channels

Bot-writable profile fields enter **only** via:
1. **Startup merge** — `BotMemorySyncService.syncFromBotMemory(userId)` on
   `ApplicationReadyEvent` (existing `BotMemoryStartupSync`).
2. **NO_APPLY write-back** — existing `writeMemoryEntry` path for rejected
   drafts.

**No new bot-writable profile endpoints are created.** The bot cannot call
`PUT /api/profile` (it uses the memory-file path only).

---

## API exposure

### `GET /api/profile` — response gains optional preferences object

```json
{
  "preferences": {
    "workModel": "REMOTE",
    "salaryFloor": 5000,
    "locations": ["São Paulo", "Remote"],
    "excludedCompanies": ["Acme Corp"]
  }
}
```

- Implemented: `ProfileResponse.preferences` (nullable), mapped by
  `ProfileController.toPreferencesResponse`.
- When no preferences are set: `"preferences": null`.

### `PUT /api/profile` — request accepts optional preferences object

```json
{
  "resumeText": "...",
  "skills": ["Java"],
  "tone": "STARTUP",
  "projects": [],
  "preferences": {
    "workModel": "HYBRID",
    "salaryFloor": 4000,
    "locations": ["Curitiba"],
    "excludedCompanies": []
  }
}
```

- Implemented: `ProfileRequest.preferences` (nullable, `@Valid` sub-object).
- **Omitted-or-null → keep (verified behavior):** when `preferences` is omitted
  or null, `UserProfileService.saveProfile` preserves the existing value —
  including preferences previously written by the bot merge. No data loss on
  plain profile updates. The same preservation applies to the resume-upload path
  (`ResumeUploadService → UserProfileService.saveProfile`).
- **Present → authoritative (verified behavior):** when `preferences` is present,
  ALL sub-fields in the request are authoritative (human override). Null
  sub-fields within the object clear the preference.
- Bean validation mirrors the domain caps: `salaryFloor` 1–500,000,
  `locations` ≤ 20 items × ≤ 100 chars, `excludedCompanies` ≤ 50 × ≤ 200.

### `POST /api/profile/upload-resume` — preserves existing preferences

The upload path never wipes preferences: the saved profile carries forward the
stored `preferences` unchanged (bot- or human-set).

---

## Alias-key policy, salary quirks, and cap determinism

### Alias-key policy (bot memory files)

`BotMemorySyncService` treats keys case-insensitively:

- Preference keys recognized: `workmodel`, `salary`, `locations`,
  `excludedcompanies` — matched via `equalsIgnoreCase`, so `WorkModel`,
  `WORKMODEL`, `Salary`, etc. all work.
- Contact aliases in `FIELD_SETTERS` (lowercased with `Locale.ROOT`):
  `contactemail`/`email`, `portfolio`/`portfoliourl`, `github`/`githuburl`,
  `linkedin`/`linkedinurl`, `phone`.
- Unknown keys are silently skipped.

### Salary quirks

- `parseSalaryFloor` strips non-digit characters first (`R$ 5.000` → 5000).
  Formatting (thousands separators, currency symbols) is tolerated.
- A leading minus sign is explicitly rejected BEFORE digit extraction, so
  `-500` yields null (never `500`).
- The 500,000 sanity cap (`MAX_SALARY_FLOOR`) applies after parsing.

### Cap-vs-throw determinism

`UserPreferences.normalizeList` validates item lengths on **all** items BEFORE
applying the item cap. This is deterministic and independent of stream
short-circuiting: an item beyond the cap that violates the length limit still
throws (never silently accepted merely because the cap was reached first).

```java
// Order:
// 1. filter null/blank  2. trim  3. validate ALL lengths  4. cap to maxItems
```

Regression covered by
`locationItem_beyondCapAndExceedingLength_shouldStillThrow`.

---

## Acceptance criteria (mapped to #50)

| # | Criterion | Test method |
|---|-----------|-------------|
| 1 | `UserPreferences` rejects salaryFloor ≤ 0 | `salaryFloor_whenZero_shouldThrow`, `salaryFloor_whenNegative_shouldThrow` |
| 2 | `UserPreferences` rejects salaryFloor > 500,000 | `salaryFloor_whenAboveSanityCap_shouldThrow` |
| 3 | `UserPreferences` normalizes null locations to empty list | `locations_whenNull_shouldNormalizeToEmpty` |
| 4 | `UserPreferences` caps locations at 20 items | `locations_whenExceeding20_shouldTrim` |
| 5 | `UserPreferences` rejects location item > 100 chars | `locationItem_whenExceeding100Chars_shouldThrow` |
| 6 | `UserPreferences` caps excludedCompanies at 50 items | `excludedCompanies_whenExceeding50_shouldTrim` |
| 7 | `UserPreferences` rejects company name > 200 chars | `companyName_whenExceeding200Chars_shouldThrow` |
| 8 | WorkModel exact-match: "remoto" → REMOTE | `parseWorkModel_remoto_shouldReturnRemote` |
| 9 | WorkModel exact-match: "hibrido" → HYBRID | `parseWorkModel_hibrido_shouldReturnHybrid` |
| 10 | WorkModel ambiguous: "maybe remote" → dropped | `parseWorkModel_ambiguousPhrase_shouldDrop` |
| 11 | WorkModel garbage: "banana" → dropped | `parseWorkModel_garbage_shouldDrop` |
| 12 | Bot merge does not overwrite human-set preferences | `mergeIntoProfile_shouldNotOverwriteHumanPreferences` |
| 13 | Bot merge fills null preferences from bot data | `mergeIntoProfile_shouldFillNullPreferences` |
| 14 | Bot merge drops absurd salary from bot data | `mergeIntoProfile_shouldDropAbsurdSalary` |
| 15 | Bot merge drops ambiguous work model tokens | `mergeIntoProfile_shouldDropAmbiguousWorkModel` |
| 16 | Bot merge logs audit lines for merged fields | `mergeIntoProfile_shouldLogAuditLines` |
| 17 | V6 columns round-trip: set → save → reload → equal; all-null → null | `saveAndReload_withPreferences_shouldRoundTrip`, `saveAndReload_allNullPreferences_shouldReturnNull` |
| 18 | Auto-send path does NOT read preferences (blast-radius) | Code audit documented above |
| 19 | Poisoned `work_model` in DB degrades to null (no read failure) | `findByUserId_withGarbageWorkModel_shouldReturnNullWorkModel` |
| 20 | PUT preserves preferences when omitted; overrides when present | `saveProfile_whenPreferencesNull_shouldPreserveExisting`, `saveProfile_whenPreferencesPresent_shouldOverride` |
| 21 | Resume upload preserves stored preferences | `uploadResume_shouldPreserveExistingPreferences` |
| 22 | GET exposes preferences; `GET` returns `preferences: null` when unset | `getProfile_withPreferences_shouldExposeThem`, `getProfile_withoutPreferences_shouldReturnNull` |

---

## Out of scope

- Per-preference-field permissions (all fields are read/write by both human and bot)
- Preferences-driven job filtering in the scraper (future: preferences-aware ranking)
- Preferences validation on the detail endpoint
- Migration of existing preference data (fresh start; null = unset)
