# Spec: Resume Upload (PDF → AI Profile Extraction)

> **Layer:** `web` | `application` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.ProfileController`
> - `com.juanperuzzo.job_hunter.application.service.ResumeUploadService`
> - `com.juanperuzzo.job_hunter.infrastructure.config.AppConfig` (upload directory)
> **Corresponding tests:** `ResumeUploadServiceTest.java`, `ResumeUploadControllerTest.java`

---

## Expected behavior

### Scenario 1: Upload valid PDF and extract profile
- **GIVEN** an authenticated user
- **WHEN** they call `POST /api/profile/upload-resume` with a valid `.pdf` file (≤ 2MB)
- **THEN** the backend extracts raw text via Apache PDFBox
- **AND** sends the raw text through `AiPort` (Hermes gateway) to extract `skills` and `projects`
- **AND** saves the PDF to `${app.upload-dir}/{userId}/resume.pdf`
- **AND** saves/overwrites the user's `UserProfile` with: extracted `resumeText`, `skills`, `projects`; `tone` unchanged (or FORMAL if new profile)
- **AND** returns `200 OK` with the full `ProfileResponse`

### Scenario 2: Upload file larger than 2MB
- **GIVEN** an authenticated user
- **WHEN** they upload a file larger than 2MB
- **THEN** returns `400 Bad Request` with a descriptive message

### Scenario 3: Upload non-PDF file
- **GIVEN** an authenticated user
- **WHEN** they upload a file that is not a PDF (e.g. `.docx`, `.png`)
- **THEN** returns `400 Bad Request` with a descriptive message

### Scenario 4: Unparseable PDF (empty or corrupted)
- **GIVEN** an authenticated user
- **WHEN** they upload a PDF from which PDFBox extracts no text
- **THEN** returns `400 Bad Request` with a descriptive message

### Scenario 5: AI extraction returns malformed JSON or wrong-shaped fields/items
- **GIVEN** an authenticated user uploading a valid PDF
- **WHEN** the Hermes gateway response is not valid JSON, lacks required fields, carries them in a non-array shape (`"skills": "Java"`, `"projects": {}`), or carries malformed items inside those arrays (a non-string skill `{"skills": ["Java", 2020]}`, a project without the `name`/`description` Prompt 3 requires `{"projects": [{"name": "X"}]}`)
- **THEN** returns `502 Bad Gateway` (AI service error), never coercing a malformed field or item into an empty list — that would overwrite real profile data with nothing

---

## Business rules

- **File type:** Only `application/pdf` is accepted (checked by content type and file extension).
- **File size:** Maximum 2MB (enforced by Spring `spring.servlet.multipart.max-file-size`).
- **`resumeText`:** The raw text extracted by PDFBox becomes the new `resumeText` (AI does not rewrite it).
- **AI prompt truncation:** Resumes longer than `ai.resume-extraction.max-chars` (default 8000) are truncated before being sent to the AI. A warning is logged. The full text is still stored as `resumeText` in the profile.
- **`skills` + `projects`:** Extracted by AI from the raw PDF text. The AI prompt must ask for these two fields as mandatory, plus `contact` as an **optional** block ("extract the candidate's contact details when present", `null` for anything not found, never invent values). Both mandatory fields accept an empty array, while absent/`null`/wrong-shaped values fail with `AiException`/`502` per Scenario 5. `contact` is parsed tolerantly when present and never mandatory.
- **`tone`:** Never extracted from the resume. Keeps the user's current `tone`, defaults to `FORMAL` if no profile exists yet.
- **PDF storage:** Saved to `${app.upload-dir}/{userId}/resume.pdf`. Overwrites any previous file for that user.
- **Profile replacement:** A successful upload completely replaces `resumeText`, `skills`, and `projects`. It is equivalent to calling `PUT /api/profile` with the extracted values.
- **No partial save:** If any step fails (PDFBox extraction, AI call, JSON parsing), the profile is not modified.

---

## Interface contract

### HTTP — `ProfileController`

| Method | Path | Auth | Content-Type | Response |
|--------|------|------|-------------|----------|
| POST | `/api/profile/upload-resume` | Bearer | `multipart/form-data` | `200` + `ProfileResponse` |

### New application service

```java
public class ResumeUploadService {
    UserProfile uploadResume(Long userId, MultipartFile file);
}
```

### Existing output ports used

- `UserProfileRepository` — save the extracted profile
- `AiPort` (`HermesAgentClient`, sole provider) — extract skills/projects from text

---

## New AI prompt (Prompt 3: Resume extraction)

**Used in:** `ResumeUploadService`
**Model:** Hermes gateway (`${HERMES_MODEL}`, default `default`); truncation via `ai.resume-extraction.max-chars`
**Expected response:** plain JSON (no markdown, no text before or after). **JSON extraction rule:** parsing tolerates deviation — every brace-balanced object in the response is collected (string-aware, markdown fences removed) and the **last** one carrying the `skills` field wins, so a reasoning block that quotes a schema-shaped object before the real payload cannot shadow it; fallback is the first object found, and no object at all raises `AiException` ("AI response contains no valid JSON"). Shared helper: `AiJsonPayloads.lastObjectWithField`. **Mandatory fields:** `skills` and `projects` must both be present (Scenario 5 — absent or `null` fails with `AiException` → `502`); an empty array is a valid value, since the prompt instructs the model to return one when nothing was found. `contact` is requested by Prompt 3 as an optional block and parsed tolerantly when present — absent, `null`, or non-object means "no contact data", never an error; `skills` and `projects` must be arrays, and a non-array shape (`"skills": "Java"`, `"projects": {}`) fails with `AiException` → `502` rather than coercing to an empty list that would overwrite existing profile data; the same rule holds one level deeper: a skill item must be a string and every project must carry a non-blank `name` and `description`, while `techStack` stays optional because the prompt allows it empty when not mentioned. Validation runs **after** selection: if the object the model answered with lacks a mandatory field, that is the error — a scratch object quoted inside a reasoning block is never persisted as a silent fallback in the stripped completion (reasoning blocks are removed client-side before selection; a *complete* scratch object quoted in unstripped prose alongside an omitting answer remains a known residual gap).

```
You are a career assistant that extracts structured data from resumes.

Extract the following fields from the resume text below.
Return ONLY a valid JSON object, with no markdown and no additional text.

Response format:
{
  "skills": ["skill 1", "skill 2", ...],
  "projects": [
    {
      "name": "<project name>",
      "description": "<short description, max 80 chars>",
      "techStack": ["tech 1", "tech 2", ...]
    }
  ],
  "contact": {
    "phone": "<phone number or null>",
    "email": "<email address or null>",
    "portfolioUrl": "<portfolio URL or null>",
    "githubUrl": "<GitHub URL or null>",
    "linkedinUrl": "<LinkedIn URL or null>"
  }
}

Rules:
- skills: extract all technical skills (languages, frameworks, tools, databases)
- projects: extract personal, academic, and professional projects mentioned.
  Each project must have a name and description. techStack can be empty if not mentioned.
- contact: extract the candidate's contact details when present in the resume.
  Use null for any field that is not found. Never invent values.
- If no skills are found, return an empty array.
- If no projects are found, return an empty array.

Resume text:
{{RAW_RESUME_TEXT}}
```

---

## Configuration

```yaml
app:
  upload-dir: ./uploads/resumes

spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 2MB

ai:
  resume-extraction:
    max-chars: 8000       # max chars sent to AI; longer text is truncated with logged warning
```

---

## Error cases

| Situation | Exception | HTTP |
|-----------|-----------|------|
| File larger than 2MB | `MaxUploadSizeExceededException` | 400 Bad Request |
| Not a PDF file | `IllegalArgumentException` | 400 Bad Request |
| Empty / corrupted PDF | `IllegalArgumentException` | 400 Bad Request |
| AI returns invalid JSON | `AiException` | 502 Bad Gateway |
| Authenticated user id not found in DB | `UserNotFoundException` | 404 Not Found |
| File write failure (disk full, permissions) | `IOException` | 500 Internal Server Error |

---

## Out of scope

- Multiple resumes per user (PDF is overwritten)
- Resume download / retrieval endpoint
- Supporting `.docx`, `.txt`, or other formats
- AI rewriting or reformatting the extracted text
- Image-based PDFs (OCR — future enhancement)
- Edição da foto de perfil

---

## CLI integration (Rust)

### Batch
```
jh --upload-resume path/to/curriculo.pdf
```
Output:
- Skills tabulated
- Projects tabulated (name, description, techStack)

### TUI
- Button "Upload PDF" on the profile screen
- Opens file picker (native dialog via `rfd`)
- On success: replaces profile screen data and shows a success banner with "Skills extracted: X | Projects extracted: Y"
- On error: shows error dialog

---

## Config storage

The uploaded PDF file path is not stored in the database at this stage (the file lives at `{upload-dir}/{userId}/resume.pdf` and can be derived from the user id).

