# Resume preview/commit flow (issue #92, slice b)

> **Status:** implemented (backend). Amended after the two-agent review of
> 2026-11-15 (fail-fast gates, blank semantics, unique-email pre-check, digest
> case-insensitivity, `techStack` as list). Bot-side rollout shipped: the
> onboarding section lives in the `job-application` skill (repo-first, issue
> #92), and slice (c) (`apply-backend-profile.md`) made the apply flow consume
> the backend profile directly — no local resume copy.

> Backend slice of #92: extract-without-committing, explicit confirmation
> before anything replaces the active resume/profile, and an account-identity
> correction route. Foundation: `bot-resume-retrieval.md` (slice a). Apply-skill
> consumption of the backend profile/PDF is slice (c) — out of scope here.

## Problem

- `POST /api/profile/upload-resume` **saves immediately** — the bot cannot show
  a preview and wait for confirmation before replacing active resume data.
  A bad PDF or a chatty correction can silently clobber the active profile.
- Operator identity (`name`/`email`) is stored in the single profile and can be
  corrected through `PUT /api/profile` or the confirmed commit request.
- #92 acceptance: "the bot receives a structured preview from the backend and
  asks for explicit confirmation before the active profile/resume is changed";
  "Re-uploading a resume cannot silently replace the active resume or manually
  set contact values."

## Contract

All endpoints require the self-hosted `X-Bot-Token` and operate on the single
operator profile.

### `POST /api/profile/resume/preview` → `200` (multipart `file`)

**Stateless by design: extracts and returns, persists nothing.**

- Runs the existing extraction pipeline (PDFBox text + AI facts) against the
  uploaded PDF and the stored profile — **without saving**.
- Response JSON:

  ```json
  {
    "accountIdentity": { "name": "...", "email": "..." },
    "contact": { "phone": "...", "contactEmail": "...", "portfolioUrl": "..." },
    "skills": ["..."],
    "projects": [{ "name": "...", "description": "...", "techStack": ["..."] }],
    "tone": "FORMAL",
    "resumeTextChars": 2412,
    "digest": "97e25856…",
    "missingFields": ["phone", "portfolioUrl"]
  }
  ```

- `accountIdentity` = the stored profile's explicit application name/email.
  `contact` = **fill-if-empty merge preview** against the
  stored profile (stored value wins over extraction — unchanged rules from
  `profile-autofill-from-resume.md`); fields still empty after the merge land
  in `missingFields` for the bot to ask in chat.
- `techStack` is a **list** (as `ResumeExtractionResponse.ExtractedProject` and
  `ProjectResponse.techStack` on the web shape), not a comma-joined string.
- Text shorter than the `saveProfile` minimum (50 chars) → `400`
  (`InvalidResumeTextException`) BEFORE any AI call — the preview never shows
  a result the commit would reject (review 2026-11-15: the two flows share one
  validity domain).
- `digest` = md5 of the uploaded bytes; the bot echoes it at commit so the
  confirmed artifact is provably the previewed one (echo is compared
  case-insensitively).
- Errors: invalid PDF / extraction failure → `400` envelope; AI failure →
  existing `AiException` mapping. The active profile and resume are untouched
  in every error path — abandoned previews cost one extraction and nothing else.

### `POST /api/profile/resume/commit` → `200` (multipart `file`, optional `corrections` part `application/json`)

**The commit call IS the explicit confirmation** — only it replaces the active
resume/profile.

- `corrections` (all optional): `{name, email, phone, contactEmail, portfolioUrl}`.
  - Validated with the existing rules (email format, length caps) **before any
    write** — name/email included, so an invalid identity correction answers
    `400` with the PDF and profile untouched (review 2026-11-15).
  - Blank = "no correction" on every route (PUT included) — a blank string is
    never an attempted update.
  - Identity corrections validate against the profile field rules before any
    write; email uniqueness across accounts does not apply in single-user mode.
  - Merge order: **chat correction > stored value > AI extraction** — a chat
    correction is an explicit user instruction and overrides; extraction keeps
    losing to stored values (fill-if-empty preserved).
- Optional `corrections.digest`: if present and != md5 of the uploaded file →
  `409` (protects against attaching a different artifact than the previewed
  one — same honesty-guard spirit as the tailoring skill gate).
- Writes: profile save (including corrected `name`/`email`) and the original PDF
  stored at `{uploadDir}/resume.pdf`. The storage adapter can still read the
  legacy `{uploadDir}/{userId}/resume.pdf` location during migration.
- Response: the standard profile response **with non-null account identity**.

### `PUT /api/profile` (amended) — optional `name` / `email`

- Account-identity correction **without re-uploading** (chat: "the name is
  wrong, fix it"). Same validation. The identity correction is validated and
  applied **before** the profile write — an invalid identity answers `400`
  with nothing saved (review 2026-11-15; the bean `@Email` is lenient, the
  service `EMAIL_PATTERN` is not, so the service rule must run first).
- Blank = "no correction" (the commit route's rule).
- Every profile response (GET / PUT / commit) carries the profile's `name` and
  `email` fields; blank corrections preserve the existing values.

## Design

- New application services `ResumePreviewService` and `ResumeCommitService`;
  extraction/validation internals are factored out of `ResumeUploadService` and
  reused (behavior preserved — `upload-resume` endpoint unchanged, TUI/CLI
  paths keep working per #92 acceptance).
- The single profile row remains the source of truth for operator identity.
- `ResumeStorage` port unchanged — no draft storage anywhere. The issue's
  "define cleanup for unconfirmed drafts" is satisfied vacuously: nothing is
  ever staged.
- **Trade-off, documented**: commit re-runs extraction (a second `AiPort` call
  per confirmed onboarding; zero for abandoned previews) instead of persisting
  a draft payload. Chosen for: no draft drift between preview and commit, no
  temp-file lifecycle, no TOCTOU. Revisit only if extraction cost matters.
- AI cost summary: preview = 1 extraction; confirmed commit = +1; abandoned
  preview = 1 total, nothing persisted.

## Bot-side rollout (after backend lands — repo-first rule)

- `job-application` skill gains the onboarding section (repo → install):
  attach → preview → summarize facts → confirm/correct in chat → commit.
  The bot never fabricates values; missing fields are asked in chat.
- The INTERIM resume-refresh section survived until slice (c) landed — (b)
  did not change the apply flow; slice (c) then removed the section and the
  local-copy rule.

## Non-goals (slice b)

- Apply-skill consumption of the backend profile and current/tailored PDF
  (slice (c) — kills `resume_path`/manual `cp` and the INTERIM rule).
- LinkedIn applications, DOCX/image PDFs or OCR, resume library/versions,
  dashboards, scheduled processing.

## Supersedes / threads

- Owns and closes slice (a) threads: write-route identity ("slice (b) owns
  them") and the first half of staleness handling (digest echo); the full kill
  of the local-copy drift is slice (c).
- `profile-autofill-from-resume.md` — merge rules unchanged, referenced.
- `resume-upload.md` — upload endpoint unchanged; amendment note: commit flow
  is the chat-confirmed path.
- `bot-resume-retrieval.md` — resume retrieval foundation; the self-hosted
  profile is the identity source.

## Testing plan (RED before GREEN)

Application (plain JUnit 5 + Mockito — no Spring context):

- `preview_whenValidPdf_shouldReturnPreviewWithoutSaving`
  (verify profile repository and storage are never written)
- `preview_whenMissingFields_shouldListThemAfterMergePreview`
- `preview_whenInvalidPdf_shouldThrow_andLeaveProfileIntact`
- `preview_whenAiFails_shouldSurfaceAiException_andLeaveProfileIntact`
- `commit_whenNoCorrections_shouldApplyFillIfEmptyMerge_andStoreResume`
- `commit_whenChatCorrection_shouldOverrideStoredValue`
- `commit_whenDigestMismatch_shouldThrowConflict_andLeaveProfileIntact`
- `commit_whenIdentityCorrected_shouldSaveCorrectedIdentity`
- `commit_whenInvalidCorrection_shouldThrowValidation`

Web (`@WebMvcTest` — house exception):

- `preview_whenValidPdf_shouldReturn200WithPreview`
- `preview_whenMissingBotToken_shouldReturn401`
- `commit_whenConfirmed_shouldReturn200WithNonNullIdentity`
- `commit_whenDigestMismatch_shouldReturn409`
- `putProfile_whenIdentityCorrected_shouldReturn200WithNonNullIdentity`
- flip the two slice-a `null`-identity assertions (save/upload routes)

Existing suites must stay green after the response-shape change.
