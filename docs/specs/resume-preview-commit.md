# Resume preview/commit flow (issue #92, slice b)

> **Status:** implemented (backend). Amended after the two-agent review of
> 2026-11-15 (fail-fast gates, blank semantics, unique-email pre-check, digest
> case-insensitivity, `techStack` as list). Bot-side rollout — the onboarding
> section in the `job-application` skill — is the repo-first follow-up after
> this lands (repo → install).

> Backend slice of #92: extract-without-committing, explicit confirmation
> before anything replaces the active resume/profile, and an account-identity
> correction route. Foundation: `bot-resume-retrieval.md` (slice a). Apply-skill
> consumption of the backend profile/PDF is slice (c) — out of scope here.

## Problem

- `POST /api/profile/upload-resume` **saves immediately** — the bot cannot show
  a preview and wait for confirmation before replacing active resume data.
  A bad PDF or a chatty correction can silently clobber the active profile.
- Account identity (`name`/`email`, users row) has **no correction route**, and
  slice (a) deliberately left write-route identity `null`.
- #92 acceptance: "the bot receives a structured preview from the backend and
  asks for explicit confirmation before the active profile/resume is changed";
  "Re-uploading a resume cannot silently replace the active resume or manually
  set contact values."

## Contract

All endpoints behind the existing auth (`X-Bot-Token` → configured owner user /
JWT self), scoped to the principal — same model as slice (a).

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

- `accountIdentity` = users row (the explicit application name/email, slice (a)
  source of truth). `contact` = **fill-if-empty merge preview** against the
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
  - Identity corrections validate against the users columns: a login email
    already used by another account is rejected with `EmailAlreadyExistsException`
    BEFORE any write (the `users.email` column is `UNIQUE`; re-submitting the
    user's own email is a no-op, not a conflict).
  - Merge order: **chat correction > stored value > AI extraction** — a chat
    correction is an explicit user instruction and overrides; extraction keeps
    losing to stored values (fill-if-empty preserved).
- Optional `corrections.digest`: if present and != md5 of the uploaded file →
  `409` (protects against attaching a different artifact than the previewed
  one — same honesty-guard spirit as the tailoring skill gate).
- Writes: profile save (existing merge/save logic), original PDF stored at
  `{uploadDir}/{userId}/resume.pdf` (unchanged convention via `ResumeStorage`),
  account identity update to the users row when `name`/`email` corrected.
- Response: the standard profile response **with non-null account identity**.

### `PUT /api/profile` (amended) — optional `name` / `email`

- Account-identity correction **without re-uploading** (chat: "the name is
  wrong, fix it"). Same validation. The identity correction is validated and
  applied **before** the profile write — an invalid identity answers `400`
  with nothing saved (review 2026-11-15; the bean `@Email` is lenient, the
  service `EMAIL_PATTERN` is not, so the service rule must run first).
- Blank = "no correction" (the commit route's rule).
- **Closes slice (a)'s write-route identity thread**: from this slice on, every
  profile response (GET / PUT / commit) carries non-null `name`/`email` from
  the users row. The existing "null identity on write routes" assertions flip.

## Design

- New application services `ResumePreviewService` and `ResumeCommitService`;
  extraction/validation internals are factored out of `ResumeUploadService` and
  reused (behavior preserved — `upload-resume` endpoint unchanged, TUI/CLI
  paths keep working per #92 acceptance).
- `UserRepository` port gains `updateIdentity(userId, name, email)`; the users
  row remains the identity source of truth (slice (a)).
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
- The INTERIM resume-refresh section survives until slice (c) — (b) does not
  change the apply flow.

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
- `bot-resume-retrieval.md` — foundation; slice (b) builds on its identity
  composition and auth model.

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
- `commit_whenIdentityCorrected_shouldUpdateUsersRow`
- `commit_whenInvalidCorrection_shouldThrowValidation`

Web (`@WebMvcTest` — house exception):

- `preview_whenValidPdf_shouldReturn200WithPreview`
- `preview_whenUnauthenticated_shouldReturn401`
- `commit_whenConfirmed_shouldReturn200WithNonNullIdentity`
- `commit_whenDigestMismatch_shouldReturn409`
- `putProfile_whenIdentityCorrected_shouldReturn200WithNonNullIdentity`
- flip the two slice-a `null`-identity assertions (save/upload routes)

Existing suites must stay green after the response-shape change.
