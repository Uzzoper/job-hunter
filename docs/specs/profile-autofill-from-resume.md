# Spec: Profile auto-fill from resume upload

> **Layer:** `application` (service + prompt) | `web` (no contract change) | docs
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.application.service.ResumeUploadService` (prompt + merge logic)
> - `docs/specs/prompts.md` (Prompt 3 versioned update)
> - `cli/src/tui/profile_screen.rs` (success banner only, optional)
> **Corresponding tests:** `ResumeUploadServiceTest.java` (new scenarios)
> **Amends:** `docs/specs/resume-upload.md` — replaces the "AI never touches contact fields" rule (v1.1) with the fill-if-empty rule below.

---

## Goal

Uploading a resume registers the **whole profile automatically**: skills, projects (already
today) **plus identity/contact fields extracted from the resume** (phone, contact email,
portfolio, GitHub, LinkedIn). The user can edit anything afterwards via the existing
`PUT /api/profile` / `jh-cli profile edit` / TUI — manual edits always win over AI.

---

## Expected behavior

### Scenario 1: first upload auto-completes the profile
- **GIVEN** a user with no profile (or a profile with empty contact fields)
- **WHEN** they upload a valid PDF whose text contains contact information
- **THEN** the AI extraction returns a `contact` object and the profile is saved with
  `phone`, `contactEmail`, `portfolioUrl`, `githubUrl`, `linkedinUrl` filled from it
- **AND** `skills`, `projects`, `resumeText` behave exactly as in `resume-upload.md`
- **AND** `tone` is still never extracted (unchanged rule)

### Scenario 2: manual edits are never wiped by a re-upload
- **GIVEN** a user whose profile already has `githubUrl = "github.com/juan"` set manually
- **WHEN** they re-upload a resume whose extracted `githubUrl` differs
- **THEN** the existing `githubUrl` is kept (fill-if-empty: AI only fills **empty** fields)
- **AND** empty contact fields ARE filled from the extraction

### Scenario 3: AI finds no contact data
- **GIVEN** a resume without (some) contact information
- **WHEN** the extraction returns `null`/missing fields in the `contact` object
- **THEN** those profile fields remain as they were (null stays null, existing stays)

### Scenario 4: invalid extracted values are dropped, not fatal
- **GIVEN** an extraction where `contactEmail` is not a valid email or any field exceeds
  the backend limits (phone ≤ 30, email ≤ 255, URLs ≤ 500 — same as `PUT /api/profile`)
- **WHEN** the profile is saved
- **THEN** only the invalid field is discarded (logged warning, treated as not-found)
- **AND** the upload still succeeds with skills/projects and any valid contacts

### Scenario 5: malformed contact object is tolerated
- **GIVEN** the AI returns a `contact` that is not an object (string, array, absent)
- **WHEN** the response is parsed
- **THEN** it is treated as "no contact data" (Scenario 3) — no error, no 502

---

## Business rules

- **Rule 1 — fill-if-empty.** Extracted contact values are applied only to fields that are
  currently null/blank on the stored profile. Existing values always win. To refresh a contact
  from a new resume the user clears the field first (CLI: `profile edit --github-url ""`).
- **Rule 2 — same validation as manual edit.** Every extracted value must pass the exact
  `PUT /api/profile` validation before being applied; invalid → dropped with a warning, never
  a failed upload.
- **Rule 3 — prompt is versioned.** Prompt 3 in `docs/specs/prompts.md` gains the `contact`
  object (`phone`, `email`, `portfolioUrl`, `githubUrl`, `linkedinUrl`; `null` when not found).
  Parsing stays tolerant: missing/`null`/non-object `contact` = no contact data.
- **Rule 4 — no API contract change.** Endpoint, request and `ProfileResponse` shapes are
  unchanged; contacts were already part of the response.
- **Rule 5 — unchanged rules from `resume-upload.md`.** File type/size, PDFBox extraction,
  truncation, tone handling, PDF storage, no-partial-save, error table — all still apply.

---

## Interface contract (port)

No port changes. `ResumeUploadService.uploadResume(Long userId, MultipartFile)` keeps its
signature; `ResumeExtractionResponse` gains an optional `contact` record:

```java
// web/dto/ResumeExtractionResponse.java
public record ResumeExtractionResponse(List<String> skills,
                                       List<ExtractedProject> projects,
                                       ExtractedContact contact) {
    public record ExtractedContact(String phone, String email,
                                   String portfolioUrl, String githubUrl, String linkedinUrl) {}
}
```

---

## Error cases

| Situation | Exception | Behavior |
|-----------|-----------|----------|
| Extracted value fails validation | — | Field dropped, `WARN` logged, upload succeeds |
| `contact` node malformed/absent | — | Treated as no contact data |
| AI/JSON failure (unchanged) | `AiException` | 502, profile untouched (no partial save) |

---

## CLI integration (TUI): drag-and-drop upload

Terminals do not transfer dragged files — they emit the file **path** as a bracketed paste
event. The TUI exploits that:

- **GIVEN** the user is on the profile screen
- **WHEN** they drag a file from the OS file manager into the terminal window
- **THEN** the TUI receives the path via crossterm `Event::Paste` (bracketed paste enabled at startup)
- **AND** if the sanitized path points to an existing `.pdf` file, the **same upload flow as the
  `rfd` file picker** runs (backend autofill included), with the usual success/error banner
- **AND** any other paste keeps its normal behavior (text into the focused field)

Sanitization rules (`sanitize_dropped_path`):
1. Trim whitespace/newlines
2. Strip surrounding single/double quotes (some terminals quote paths)
3. Strip a leading `file://` URI prefix and decode common percent-escapes (`%20` → space)
4. Accept only if the resulting path exists and has a `.pdf` extension (case-insensitive)

Rules:
- Drag-drop detection happens **only on the profile screen**; other screens treat pastes as text
- Trade-off (accepted): pasting a literal path to an existing `.pdf` into a text field triggers
  the upload instead of inserting text — rare and recoverable
- Terminal support: bracketed paste covers gnome-terminal, Konsole, Alacritty, WezTerm, kitty,
  Windows Terminal; emulators without it fall back to the `rfd` picker and
  `jh-cli profile upload <path>` (unchanged); inside tmux requires clipboard passthrough (documented)

---

## Out of scope

- Does not extract or update `users.name` (auth identity — registered at signup)
- Does not extract `tone` (unchanged rule)
- Does not add endpoints or change request/response shapes
- Does not add OCR for image-based PDFs (still future)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/profile-autofill-from-resume.md and the amended
docs/specs/resume-upload.md.

Step 1 — write the new ResumeUploadServiceTest scenarios (RED):
fill-if-empty, no-contact-data, invalid-value-dropped, malformed-contact.
Also write the TUI sanitize_dropped_path unit tests (RED).
Do not change production code beyond compile stubs.

Step 2 — wait for confirmation before implementing.
```
