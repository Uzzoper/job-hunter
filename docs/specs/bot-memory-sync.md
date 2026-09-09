# Spec: Bot Memory Sync Service

> **Issue:** #31
> **Layer:** `application` (service) · `infrastructure` (filesystem adapter) · `domain` (value object + exception)
> **Implementation files:**
> - `domain/model/BotPreferences.java` (value object)
> - `domain/exception/BotMemorySyncException.java`
> - `application/port/out/BotMemoryPort.java` (outbound port)
> - `application/service/BotMemorySyncService.java`
> - `infrastructure/botmemory/FileSystemBotMemoryAdapter.java`
> - `infrastructure/config/AppConfig.java` (wiring + @Value)
> - `resources/application.yaml` (config defaults)
> - `web/exception/GlobalExceptionHandler.java` (exception mapping)
> **Tests:** `unit/application/BotMemorySyncServiceTest.java`

---

## Goal

The Hermes bot (`jobhunter-bot` profile) maintains free-text memory files
(`memories/MEMORY.md`, `memories/USER.md`) that accumulate user preferences,
location constraints, salary expectations, and interaction history. This service
bridges those files into the Job Hunter backend so the profile and analysis
pipeline can benefit from what the bot has learned — and bot decisions (e.g.
rejected drafts) can be written back as new memory sections.

---

## Context

- **Bot profile dir:** configurable via `bot.memory.dir` (default
  `${user.home}/.hermes/profiles/jobhunter-bot`). Both the Java backend and the
  Hermes bot run as the same OS user, so direct `java.nio.file.Files` access is
  safe.
- **File format:** free-text sections separated by `§` lines (NOT JSON). Within a
  section, lines may optionally follow a `key: value` format.
- **Startup:** the bot profile directory may not exist on every developer machine
  (CI, fresh clones). The service MUST log a WARN and continue — never fail
  startup.
- **User-scoped:** all operations take a `userId`. Currently the bot memory files
  are shared (single bot profile), but the architecture supports future per-user
  paths via `bot.memory.user-file-pattern`.

---

## § file format specification

### Delimiter
Sections are delimited by a line containing exactly `§` (U+00A7, one character,
possibly surrounded by whitespace).

### Section content
Each section is free-text. Within a section, individual lines MAY follow a
`key: value` pattern where the key is a non-empty string before the first `:`
and the value is everything after.

### Parsing rules
1. Split the file content on `§` (lines matching `/^\s*§\s*$/`).
2. Trim each resulting chunk; discard empty chunks.
3. For each section, extract `key: value` pairs from lines matching
   `/^([^:]{1,80}):\s*(.+)$/`. The key is trimmed, the value is trimmed.
4. Non-matching lines are kept as raw text.
5. `BotPreferences` aggregates:
   - `keyValues`: `Map<String, String>` — all extracted key-value pairs (last
     value wins on duplicate keys across sections).
   - `rawSections`: `List<String>` — all non-empty section texts, preserving
     order.

### Example

Input (`MEMORY.md`):
```
§
Locations: City X (remote only). Discard onsite/hybrid in City Y.
§
Salary: Z range
§
General notes from conversation
```

Parsed:
```
keyValues = { "Locations": "City X (remote only). Discard onsite/hybrid in City Y.",
              "Salary": "Z range" }
rawSections = [ "Locations: City X (remote only). Discard onsite/hybrid in City Y.",
                "Salary: Z range",
                "General notes from conversation" ]
```

---

## Merge rules (bot memory → UserProfile)

The service reads `BotPreferences` and applies a **fill-if-empty merge** into the
stored `UserProfile`, following the same precedent as
`ResumeUploadService.mergeContactField`:

### Key mapping (generic, not hardcoded)

A configurable mapping associates BotPreferences keys with UserProfile fields:

| BotPreferences key (case-insensitive) | UserProfile field |
|---------------------------------------|-------------------|
| `contactEmail` / `email`              | `contactEmail`    |
| `phone`                               | `phone`           |
| `portfolioUrl` / `portfolio`          | `portfolioUrl`    |
| `githubUrl` / `github`               | `githubUrl`       |
| `linkedinUrl` / `linkedin`           | `linkedinUrl`     |

- **Rule 1 — fill-if-empty:** A mapped value is applied only when the
  corresponding `UserProfile` field is null/blank. Existing values always win.
- **Rule 2 — unmapped keys:** Keys not in the mapping are silently ignored for
  the profile merge (they remain accessible via `BotPreferences` for consumers
  that need the raw data).
- **Rule 3 — raw sections:** Raw text sections are never merged into UserProfile.
  They are informational context only.

---

## Write-back trigger

### On draft rejection (`writeMemoryEntry`)

When the bot rejects an email draft, the rejection reason is appended as a new
section to `MEMORY.md`:

```
§
<free-text content provided by caller>
```

This is a simple append operation via `BotMemoryPort.appendSection`.

---

## Startup behavior

On `ApplicationReadyEvent`, the service attempts to sync bot memory for all
registered users.

| Condition | Behavior |
|-----------|----------|
| `bot.memory.dir` does not exist | `WARN` log, skip sync, continue startup |
| MEMORY.md does not exist | `WARN` log, skip, continue |
| MEMORY.md exists but is empty | No-op, continue |
| Parse produces no keyValues | Continue (profile untouched) |
| Profile does not exist for user | Skip that user, `WARN` log |
| File I/O error | `BotMemorySyncException`, logged as `ERROR`, does NOT fail startup |

---

## Components

### Domain

#### `BotPreferences` (value object)
```java
public record BotPreferences(
    Map<String, String> keyValues,
    List<String> rawSections
) {
    // compact constructor: null-safe defaults
}
```

#### `BotMemorySyncException` (exception)
```java
public class BotMemorySyncException extends RuntimeException {
    public BotMemorySyncException(String message);
    public BotMemorySyncException(String message, Throwable cause);
}
```
Mapped in `GlobalExceptionHandler` → HTTP 500 (infrastructure error, not user fault).

### Application

#### `BotMemoryPort` (outbound port)
```java
public interface BotMemoryPort {
    Optional<String> readFile(Path path);
    void appendSection(Path path, String sectionText);
}
```

#### `BotMemorySyncService`
```java
public class BotMemorySyncService {
    BotMemorySyncService(BotMemoryPort botMemoryPort,
                         UserProfileRepository userProfileRepository,
                         Path memoryDir,
                         String memoryFileName,
                         String userFileName);

    // Read + parse + merge
    void syncFromBotMemory(Long userId);

    // Parse-only (used by tests and by syncFromBotMemory)
    BotPreferences parseMemoryContent(String content);

    // Write-back
    void writeMemoryEntry(Long userId, String text);
}
```

### Infrastructure

#### `FileSystemBotMemoryAdapter`
Implements `BotMemoryPort`. Pure `java.nio.file.Files` operations. No Spring
annotations beyond `@Component` (wired via `AppConfig`).

---

## Acceptance criteria (mapped to #31)

| # | Criterion | Test method |
|---|-----------|-------------|
| 1 | Parse § sections into keyValues + rawSections | `readMemoryFile_shouldParsePreferences` |
| 2 | Append new section to MEMORY.md | `writeMemoryEntry_shouldAppendToFile` |
| 3 | Missing file returns empty/does not throw | `readMemoryFile_whenFileMissing_shouldReturnEmpty` |
| 4 | Blank/empty content produces empty BotPreferences | `parseContent_whenBlank_shouldReturnEmptyPreferences` |
| 5 | Fill-if-empty merge respects existing profile values | `syncFromBotMemory_shouldNotOverwriteExistingFields` |
| 6 | Startup does not fail when files absent | `syncFromBotMemory_whenMemoryDirMissing_shouldLogAndContinue` |
| 7 | Duplicate keys across sections → last wins | `readMemoryFile_whenDuplicateKeys_shouldUseLastValue` |

---

## Out of scope

- Per-user memory files (future: `bot.memory.user-file-pattern`)
- OCR / structured extraction from USER.md beyond § parsing
- Real-time sync (webhook/event from bot) — startup + manual triggers only
- `memails/` directory (handled by `scripts/install-bot-skills.sh`)
