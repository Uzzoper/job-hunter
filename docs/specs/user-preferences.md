# Spec: User Work Preferences Model with Anti-Hallucination Guards

> **Issue:** #50
> **Layer:** `domain` (model) · `application` (service merge) · `infrastructure` (persistence) · `web` (DTO exposure)
> **Implementation files:**
> - `domain/model/WorkPreference.java` (sealed sum type — Remote | Hybrid | Onsite)
> - `domain/model/UserPreferences.java` (value object with validation)
> - `domain/model/UserProfile.java` (extended with nullable preferences)
> - `infrastructure/persistence/WorkPreferenceConverter.java` (discriminated-JSON JPA converter)
> - `infrastructure/persistence/UserProfileEntity.java` (new `work_preference` column)
> - `infrastructure/persistence/UserProfilePersistenceAdapter.java` (field mapping, poisoned-read fallback)
> - `application/service/BotMemorySyncService.java` (parse-lenient / validate-strict firewall)
> - `resources/db/migration/V6__add_user_preferences_columns.sql` (legacy — kept valid, superseded)
> - `resources/db/migration/V7__add_work_preference_column.sql` (sum-type column)
> - `web/dto/ProfileRequest.java` (optional preferences fields)
> - `web/dto/PreferencesRequest.java` (preferences request sub-object with bean validation)
> - `web/dto/WorkPreferenceDto.java` (discriminated JSON variant DTO)
> - `web/dto/ProfileResponse.java` (preferences exposure)
> - `web/dto/PreferencesResponse.java` (preferences response sub-object)
> - `web/controller/ProfileController.java` (pass-through + preserve-on-null via service)
> **Removed:**
> - `domain/model/WorkModel.java` (enum superseded by `WorkPreference`)
> **Tests:**
> - `unit/domain/UserPreferencesTest.java` (adversarial validation incl. `WorkPreference` sum type)
> - `unit/application/BotMemorySyncPreferencesTest.java` (adversarial merge + parse firewall)
> - `unit/web/ProfileControllerTest.java` (discriminated JSON + validation)
> - `unit/application/UserProfileServiceTest.java`, `unit/application/ResumeUploadServiceTest.java`
> - `integration/SqliteBaselineIntegrationTest.java` (V7 round-trip + poisoned read)

---

## Goal

Model user work-preference data (`workPreference`, `salaryFloor`,
`excludedCompanies`) as a domain value object with strict validation, fed into
the profile by the Hermes bot's memory-merge path and by the user via the API.
Bot-sourced values go through a **parse-lenient / validate-strict** firewall
that normalizes, exact-matches, and sanity-caps raw strings before they ever
touch persistence — eliminating hallucinated, ambiguous, or absurd values.

The work-location dimension is modeled as a **sealed sum type**
(`WorkPreference`): *Remote* (no cities), *Hybrid* (≥ 1 city), or *Onsite*
(≥ 1 city). Structurally, **contradictory states are unrepresentable** — there
is no way to express "remoto" with a city list (City + Remote), no way to
express Hybrid/Onsite without cities, and no way to store a city list without a
concrete model. The compiler enforces exhaustiveness; the value-object
constructors enforce the invariants.

---

## Context

- **Existing `UserProfile`** holds contact fields, skills, tone, and projects.
  Preferences are a nullable composition — no existing row has them.
- **Bot memory merge** (`BotMemorySyncService.mergeIntoProfile`) already follows
  fill-if-empty semantics: human-set values always win.
- **Auto-send** (`AutoSendEligibilityService` / `AutoSendScheduler`) operates on
  `matchScore` and `EmailStatus` only — it never reads preferences today. This
  blast-radius invariant is documented below and verified in code.
- **TUI / API** (`ProfileController PUT /api/profile`) allows the user to set
  preferences directly; these are authoritative and never overwritten by bot data.
- **Issue #50 evolution**: the original design paired a loose `WorkModel` enum
  with an independent `locations` list, which allowed contradictory states
  (e.g. `REMOTE` + `locations: ["Curitiba"]`, or `HYBRID` with no cities).
  This spec replaces that pair with the sealed `WorkPreference` sum type.

---

## Evolution decision (documented)

- **Outright replace** (not deprecated-in-place): `WorkModel` and the
  `locations` list are removed from `UserPreferences` and replaced by the single
  `WorkPreference workPreference` field. Rationale: both fields existed only on
  this feature branch (#50, uncommitted at review time); test fixtures update
  cleanly; keeping dual fields would preserve the representable-contradiction
  loophole the sum type exists to close.
- **Migration stays additive**: `V6` is left untouched (it has run on shared
  environments). `V7` adds one nullable column `work_preference TEXT`.
  Existing rows carry `NULL` in that column = preference unknown. The V6
  columns (`work_model`, `locations`) remain in the schema but become
  **legacy/unmapped**: the persistence adapter reads and writes only the new
  column after V7. No data backfill — V6-era rows with `work_model` set are
  treated as unknown (`NULL` variant), which is the documented migration
  semantic.
- **API breaking change**: the `preferences.workModel` + `preferences.locations`
  sub-fields are replaced by the single discriminated
  `preferences.workPreference` object. A bare city list without a model is no
  longer expressible by design.

---

## Domain model

### `WorkPreference` sealed sum type (pure Java, zero Spring)

```java
public sealed interface WorkPreference permits WorkPreference.Remote, WorkPreference.Hybrid, WorkPreference.Onsite {

    int MAX_CITIES = 20;
    int MAX_CITY_LENGTH = 100;

    record Remote() implements WorkPreference {}

    record Hybrid(List<String> cities) implements WorkPreference {
        // compact ctor: ≥ 1 non-blank city; trim/drop blanks; cap at MAX_CITIES;
        // any city > MAX_CITY_LENGTH → IllegalArgumentException
    }

    record Onsite(List<String> cities) implements WorkPreference {
        // same compact-ctor rules as Hybrid
    }
}
```

**Invariants (compact constructors):**
- `Remote` carries no data — "I'll work from anywhere / no city constraint".
- `Hybrid` and `Onsite` **require ≥ 1 non-blank city**: `null`,
  empty, or all-blank input → `IllegalArgumentException`.
- City items are trimmed; blank items are dropped; `null` items are dropped.
- City length cap: ≥ 1 item over `MAX_CITY_LENGTH` (100) → `IllegalArgumentException`
  (same *throw* family as locations previously).
- City count cap: excess over `MAX_CITIES` (20) → silently trimmed (same
  *trim* family as locations previously).
- Deterministic cap: length validation runs on **all** items BEFORE the count
  cap, so an item beyond the cap that exceeds the length limit still throws
  (no stream short-circuiting).

The sum type is **contradiction-free by construction**:
- Remote + cities → unrepresentable (Remote has no fields; `parseWorkPreference`
  for a remote token ignores any city list).
- Hybrid/Onsite without cities → `IllegalArgumentException`.
- City list without a model → unrepresentable (there is no field for it).

### `UserPreferences` record

```java
public record UserPreferences(
    WorkPreference workPreference,   // nullable — null when unset
    Integer salaryFloor,             // nullable — null when unset; must be > 0 if present
    List<String> excludedCompanies   // nullable → normalized to empty list; max 50 items, each max 200 chars
) {
    // compact constructor: validation + normalization (salaryFloor, excludedCompanies)
    // constants: MAX_EXCLUDED_COMPANIES=50, MAX_COMPANY_NAME_LENGTH=200, MAX_SALARY_FLOOR=500_000
}
```

**Validation rules (compact constructor):**

| Field | Rule | On violation |
|-------|------|-------------|
| `workPreference` | nullable | null passes through |
| `salaryFloor` | nullable; if present, must be > 0 | `IllegalArgumentException` |
| `salaryFloor` | if present, must be ≤ 500,000 (sanity cap) | `IllegalArgumentException` |
| `excludedCompanies` | nullable → `List.of()`; at most 50 items | excess silently trimmed |
| `excludedCompanies` items | non-null, trimmed; max 200 chars each | `IllegalArgumentException` if any item exceeds |

---

## UserProfile extension

`UserProfile` carries one nullable field **at the end of the record**:

```java
public record UserProfile(
    // ... existing fields ...
    UserPreferences preferences   // nullable — null = no preferences set
) {}
```

The compact constructor normalizes `null` preferences to `null` (no default
object — absence is explicit).

---

## Migration plan

### V6 (legacy — kept valid, never modified)

```sql
ALTER TABLE user_profiles ADD COLUMN work_model VARCHAR(20);
ALTER TABLE user_profiles ADD COLUMN salary_floor INTEGER;
ALTER TABLE user_profiles ADD COLUMN locations TEXT;          -- JSON via StringListConverter
ALTER TABLE user_profiles ADD COLUMN excluded_companies TEXT; -- JSON via StringListConverter
```

### V7 (new — additive)

```sql
-- V7: add sealed work-preference column (Remote | Hybrid | Onsite sum type)
-- Additive only: V6 stays valid. Existing V6 columns (work_model, locations)
-- are no longer mapped by the persistence layer after this migration; they
-- remain in the schema untouched. NULL work_preference = preference unknown.
ALTER TABLE user_profiles ADD COLUMN work_preference TEXT; -- discriminated JSON via WorkPreferenceConverter
```

- **Existing rows** have `work_preference = NULL` → parsed as preference
  "unknown" (identical to the all-null preference case).
- **No data backfill** from V6 columns — V6-era values are intentionally not
  carried forward (their meaning cannot be mapped unambiguously onto the sum
  type; e.g. `work_model=REMOTE` + `locations=[X]`).
- New saves write only `work_preference` (plus `salary_floor` /
  `excluded_companies`).

### V8 (planned — dead-column retirement, NOT yet created)

The V6 columns `work_model` and `locations` are **dead columns** after V7: the
persistence layer no longer maps or writes them, so user-visible behavior is
identical whether or not they exist. Deleting them is deferred on purpose —
SQLite `DROP COLUMN` is a full rewrite of the table and invalidates any
long-lived read-only connection or backup tooling that still references the
old schema.

- **Trigger**: apply V8 only after production re-entry is confirmed and at least
  one deploy cycle has run fully on V7 (so every prod instance is guaranteed to
  have the new column and no code that reads the legacy columns).
- **Planned migration** (SQLite ≥ 3.35 supports `DROP COLUMN`):
  ```sql
  -- V8: retire legacy V6 preference columns (dead since V7)
  ALTER TABLE user_profiles DROP COLUMN work_model;
  ALTER TABLE user_profiles DROP COLUMN locations;
  ```
- **Pre-flight notice (startup)**: before issuing V8, run a startup/one-off
  check that detects legacy columns still holding non-null data and logs a
  warning, so operators know whether any V6-era data would be silently lost.
  Suggested check (runs only when `work_preference` exists and
  `work_model`/`locations` still exist):
  ```sql
  SELECT COUNT(*) FROM user_profiles
   WHERE work_model IS NOT NULL OR locations IS NOT NULL;
  ```
  A non-zero count → `log.warn("N user_profiles still carry legacy V6
  work_model/locations data that will be dropped by V8")`. Zero → silent.
- **Rollback note**: V8 is destructive and one-way; if rollback to V6 ever
  becomes necessary it requires a pre-V8 backup restore, not a revert.

---

## Persistence mapping

`UserProfileEntity`:

```java
@Column(name = "work_preference")
@Convert(converter = WorkPreferenceConverter.class)
private WorkPreference workPreference;   // replaces work_model + locations

@Column(name = "salary_floor")
private Integer salaryFloor;

@Column(name = "excluded_companies")
@Convert(converter = StringListConverter.class)
private String[] excludedCompanies;
```

`WorkPreferenceConverter` (JPA `AttributeConverter`, following the
`StringListConverter` precedent) serializes the variant to discriminated JSON:

| Domain | Column text |
|--------|-------------|
| `WorkPreference.Remote()` | `{"type":"remote"}` |
| `WorkPreference.Hybrid([..])` | `{"type":"hybrid","cities":["Curitiba","São Paulo"]}` |
| `WorkPreference.Onsite([..])` | `{"type":"onsite","cities":["Curitiba"]}` |

Read direction is **poisoned-read tolerant**: unknown `type`, malformed JSON,
or a structurally invalid city list (e.g. hybrid with no cities from a
hand-edited DB) is caught, logged at WARN, and yields `null` instead of
failing the whole profile read.

`UserProfilePersistenceAdapter`:
- `toEntity`: maps `preferences.workPreference()`, `preferences.salaryFloor()`,
  `excludedCompanies` via `StringListConverter`.
- `toDomain`: reconstructs `UserPreferences` from entity fields; all-null
  columns → `null` preferences.

---

## Parse-lenient / validate-strict firewall

Raw bot memory values enter via `BotMemorySyncService.mergeIntoProfile`. Before
they touch `UserPreferences`, they pass through a normalization + validation
layer.

### WorkPreference tokens (exact-match after normalization)

`parseWorkPreference(rawToken, cities)`:

1. **Normalize token**: lowercase + strip diacritics (NFD decomposition) + trim.
2. **Exact-match** the token:

| Normalized token | City list | Result |
|------------------|-----------|--------|
| `remoto`, `remote` | any (ignored) | `WorkPreference.Remote()` |
| `hibrido`, `hybrid` | ≥ 1 non-blank city, ≤ 20, each ≤ 100 chars | `WorkPreference.Hybrid(cities)` |
| `hibrido`, `hybrid` | empty/missing | dropped with WARN (unrepresentable) |
| `presencial`, `onsite`, `on-site`, `on site` | ≥ 1 non-blank city | `WorkPreference.Onsite(cities)` |
| `presencial`, `onsite`, ... | empty/missing | dropped with WARN (unrepresentable) |
| anything else | — | dropped with WARN |

City extraction: `parseCities(raw)` splits on commas, trims, drops blanks and
length-exceeding items, caps at `MAX_CITIES` (20). For Remote, a `locations`
line in the same merge is **ignored** (remote implies no city constraint); it is
not an error, it is simply unrepresentable — documented in the audit section.

Anti-hallucination contract: a token is never "guessed". `remoto` alone is
exactly `Remote`; `hibrido` alone is **never** given invented cities and is
**never** degraded to Remote/Onsite — it is dropped.

### SalaryFloor (digits-only with sanity cap) — unchanged

1. Reject leading minus sign BEFORE extraction (`-500` → null).
2. Strip non-digit characters, `Integer.parseInt`.
3. Must be > 0 and ≤ `MAX_SALARY_FLOOR` (500,000) else dropped with WARN.

### ExcludedCompanies (list with bounds) — unchanged

Split on commas → trim → drop blanks + over-length items → cap at 50.

### Audit trail (unchanged)

When a bot-sourced value is merged, the merge logs at INFO level:
```
"Merged preferences for user {}: workPreference=null→{variant}, salaryFloor=old→new (source: '<raw snippet>')"
```

---

## Fill-if-empty semantics (unchanged)

- **Human-set values (TUI/API)** always win — fill only when the current value
  is null/empty.
- **Bot values** only fill fields that are currently null/empty.
- **Null preference object** on profile = all fields unfilled → bot data fills all.
- **Partial preference object** (e.g. only `workPreference` set) = bot data
  fills remaining null fields only (`salaryFloor`, `excludedCompanies`).

---

## Blast-radius invariant (unchanged)

**Invariant:** Bot-sourced `UserPreferences` data feeds ranking/filter/draft
generation only. It NEVER gates the auto-apply/auto-send path.

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

## Narrow channels (unchanged)

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
    "workPreference": { "type": "remote" },
    "salaryFloor": 5000,
    "excludedCompanies": ["Acme Corp"]
  }
}
```

```json
{
  "preferences": {
    "workPreference": { "type": "hybrid", "cities": ["Curitiba", "São Paulo"] },
    "salaryFloor": 4000,
    "excludedCompanies": []
  }
}
```

- Implemented: `ProfileResponse.preferences` (nullable), mapped by
  `ProfileController.toPreferencesResponse` via `WorkPreferenceDto`.
- Discriminated JSON: the `type` property (`remote` | `hybrid` | `onsite`);
  `cities` present only for `hybrid`/`onsite`.
- When no preferences are set: `"preferences": null`.

### `PUT /api/profile` — request accepts optional preferences object

```json
{
  "resumeText": "...",
  "skills": ["Java"],
  "tone": "STARTUP",
  "projects": [],
  "preferences": {
    "workPreference": { "type": "hybrid", "cities": ["Curitiba"] },
    "salaryFloor": 4000,
    "excludedCompanies": []
  }
}
```

- Implemented: `ProfileRequest.preferences` (nullable, `@Valid` sub-object),
  `WorkPreferenceDto` with `@JsonTypeInfo(property = "type")` + `@JsonSubTypes`.
- **Validation on input:** `hybrid`/`onsite` without `cities` (missing, null, or
  empty) → 400; `cities` > 20 items or any city > 100 chars → 400 (bean
  validation mirrors the domain caps); unknown `type` → 400 (malformed JSON).
- **Omitted-or-null → keep (verified behavior):** when `preferences` is omitted
  or null, `UserProfileService.saveProfile` preserves the existing value —
  including preferences previously written by the bot merge. No data loss on
  plain profile updates. The same preservation applies to the resume-upload path
  (`ResumeUploadService → UserProfileService.saveProfile`).
- **Present → authoritative (verified behavior):** when `preferences` is present,
  ALL sub-fields in the request are authoritative (human override). Null
  sub-fields within the object clear the preference.

### Breaking change vs. previous #50 shape

`preferences.workModel` (enum string) and `preferences.locations` (bare city
list) are removed. Clients must send the discriminated `workPreference` object:

| Old | New |
|-----|-----|
| `{"workModel":"REMOTE"}` | `{"workPreference":{"type":"remote"}}` |
| `{"workModel":"HYBRID","locations":["Curitiba"]}` | `{"workPreference":{"type":"hybrid","cities":["Curitiba"]}}` |
| `{"workModel":"ONSITE","locations":["São Paulo"]}` | `{"workPreference":{"type":"onsite","cities":["São Paulo"]}}` |
| `{"locations":["Curitiba"]}` (no model) | not expressible — must pick remote/hybrid/onsite |

### `POST /api/profile/upload-resume` — preserves existing preferences

The upload path never wipes preferences: the saved profile carries forward the
stored `preferences` unchanged (bot- or human-set).

---

## Alias-key policy, salary quirks, and cap determinism

### Alias-key policy (bot memory files)

`BotMemorySyncService` treats keys case-insensitively:

- Preference keys recognized: `workmodel`, `locations`, `salary`,
  `excludedcompanies` — matched via `equalsIgnoreCase`, so `WorkModel`,
  `WORKMODEL`, `Salary`, etc. all work. The `workmodel` value carries the model
  token; the `locations` value carries the city list for hybrid/onsite.
- Contact aliases in `FIELD_SETTERS` (lowercased with `Locale.ROOT`):
  `contactemail`/`email`, `portfolio`/`portfoliourl`, `github`/`githuburl`,
  `linkedin`/`linkedinurl`, `phone`.
- Unknown keys are silently skipped.

### Salary quirks (unchanged)

- `parseSalaryFloor` strips non-digit characters first (`R$ 5.000` → 5000).
- A leading minus sign is explicitly rejected BEFORE digit extraction, so
  `-500` yields null (never `500`).
- The 500,000 sanity cap (`MAX_SALARY_FLOOR`) applies after parsing.

### Cap-vs-throw determinism

City validation follows the locations family and is deterministic: item lengths
are validated on **all** items BEFORE the count cap. Multiline normal —
collect → validate ALL → cap → records. Regression covered by
`hybrid_whenCityBeyondCapExceedsLength_shouldStillThrow`.

---

## Acceptance criteria

| # | Criterion | Test method |
|---|-----------|-------------|
| 1 | `WorkPreference.Remote` exists and is closed (3 permitted variants) | `remote_shouldExist` |
| 2 | `Hybrid` rejects null/empty/all-blank city lists | `hybrid_whenNullCities_shouldThrow`, `hybrid_whenEmptyCities_shouldThrow`, `hybrid_whenAllBlankCities_shouldThrow` |
| 3 | `Hybrid` trims cities, drops blanks and null items | `hybrid_shouldTrimAndDropBlankCities`, `hybrid_shouldAcceptNullItems` |
| 4 | `Hybrid` caps at 20 cities; city > 100 chars throws (deterministic) | `hybrid_whenExceeding20Cities_shouldTrim`, `hybrid_whenCityExceeds100Chars_shouldThrow`, `hybrid_whenCityBeyondCapExceedsLength_shouldStillThrow` |
| 5 | `Onsite` mirrors Hybrid rules | `onsite_whenNullCities_shouldThrow`, `onsite_whenValidCities_shouldAccept` |
| 6 | `UserPreferences` rejects salaryFloor ≤ 0 / > 500,000 | `salaryFloor_whenZero_shouldThrow`, `salaryFloor_whenNegative_shouldThrow`, `salaryFloor_whenAboveSanityCap_shouldThrow` |
| 7 | `UserPreferences` normalizes excludedCompanies (caps) | `excludedCompanies_whenExceeding50_shouldTrim`, `companyName_whenExceeding200Chars_shouldThrow` |
| 8 | WorkPreference exact-match: "remoto"/"remote" → Remote (cities ignored) | `parseWorkPreference_remoto_shouldReturnRemote`, `parseWorkPreference_remote_ignoresCityList` |
| 9 | WorkPreference exact-match: "hibrido"/"hybrid" + cities → Hybrid | `parseWorkPreference_hibrido_shouldReturnHybrid`, `parseWorkPreference_hybrid_shouldReturnHybrid` |
| 10 | Hybrid/Onsite without cities → dropped (never invented) | `parseWorkPreference_hybridWithoutCities_shouldDrop`, `parseWorkPreference_onsiteWithoutCities_shouldDrop` |
| 11 | WorkPreference exact-match: "presencial"/"on-site" + cities → Onsite | `parseWorkPreference_presencial_shouldReturnOnsite`, `parseWorkPreference_onsite_shouldReturnOnsite` |
| 12 | WorkPreference ambiguous/garbage/free-text → dropped | `parseWorkPreference_ambiguousPhrase_shouldDrop`, `parseWorkPreference_garbage_shouldDrop`, `parseWorkPreference_freeText_shouldDrop` |
| 13 | parseCities: comma-separated, caps at 20, drops > 100 chars | `parseCities_commaSeparated_shouldParse`, `parseCities_whenExceeding20_shouldTrim`, `parseCities_longItem_shouldDrop` |
| 14 | Bot merge does not overwrite human-set preferences | `mergeIntoProfile_shouldNotOverwriteHumanPreferences` |
| 15 | Bot merge fills null preferences (hybrid + cities) | `mergeIntoProfile_shouldFillNullPreferences` |
| 16 | Bot merge drops absurd salary from bot data | `mergeIntoProfile_shouldDropAbsurdSalary` |
| 17 | Bot merge drops ambiguous work model tokens | `mergeIntoProfile_shouldDropAmbiguousWorkModel` |
| 18 | Bot merge drops hybrid without cities (unrepresentable) | `mergeIntoProfile_shouldDropHybridWithoutCities` |
| 19 | Bot merge ignores locations for Remote (remote implies no cities) | `mergeIntoProfile_shouldIgnoreLocationsForRemote` |
| 20 | Bot merge logs audit lines for merged fields | `mergeIntoProfile_shouldLogAuditLines` |
| 21 | V7 round-trip: set → save → reload → equal; all-null → null | `saveAndReload_withWorkPreference_shouldRoundTrip`, `saveAndReload_allNullPreferences_shouldReturnNull` |
| 22 | Auto-send path does NOT read preferences (blast-radius unchanged) | Code audit documented above |
| 23 | Poisoned `work_preference` in DB degrades to null (no read failure) | `findByUserId_withGarbageWorkPreference_shouldReturnNullWorkPreference` |
| 24 | PUT preserves preferences when omitted; overrides when present | `saveProfile_withoutPreferences_shouldPreserveExisting`, `saveProfile_withPreferences_shouldOverride` |
| 25 | PUT rejects hybrid/onsite without cities (400) | `saveProfile_whenHybridWithoutCities_shouldReturn400` |
| 26 | PUT rejects unknown `type`, empty `cities: []`, and blank-string cities (400) | `saveProfile_whenUnknownWorkPreferenceType_shouldReturn400`, `saveProfile_whenHybridEmptyCities_shouldReturn400`, `saveProfile_whenOnsiteBlankStringCities_shouldReturn400` |
| 27 | Resume upload preserves stored preferences | `uploadResume_shouldPreserveExistingPreferences` |
| 28 | GET exposes typed `workPreference`; `preferences: null` when unset | `getProfile_withPreferences_shouldExposeThem`, `getProfile_withoutPreferences_shouldReturnNull` |
| 29 | Converter hostile input (malformed JSON, missing `type`, cities-as-string/object, unknown type) degrades to null | `WorkPreferenceConverterTest` |
| 30 | Bot merge audit INFO lines are actually emitted (log capture) | `mergeIntoProfile_shouldLogAuditLines` |
| 31 | Resume-upload passes null preferences and relies on service-layer preserve | `uploadResume_shouldPreserveExistingPreferences` (ArgumentCaptor assertion) |
| 32 | V8 dead-column retirement is documented (not yet created) | Migration plan §V8 |

---

## Out of scope

- Per-preference-field permissions (all fields are read/write by both human and bot)
- Preferences-driven job filtering in the scraper (future: preferences-aware ranking)
- Preferences validation on the detail endpoint
- Backfill of V6-era `work_model`/`locations` data into `work_preference`
  (documented design decision: V6-era values are treated as unknown)