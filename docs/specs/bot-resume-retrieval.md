# Bot resume retrieval + profile account identity (issue #92, slice a)

Small backend slice: the bot can retrieve the stored resume PDF through the
authenticated API, and the profile response carries the account identity.
It is the foundation for slice (b) (preview/commit upload contract) and
slice (c) (apply skill consumes the backend profile).

## Problem

1. The backend stores the uploaded PDF at `{upload-dir}/{userId}/resume.pdf`
   (resume-upload spec) but **no endpoint serves it**. The apply skill runs
   on a hand-maintained local copy on the bot host (`mcp-apply-loop.md`,
   `resume_path`) — two unsynchronized artifacts of the same PDF; 87
   applications were sent through that manual path (evidence 2026-11-11).
2. `GET /api/profile` does not carry the account identity (`User.name()` /
   `User.email()`) — clients must call login/register to learn who the user
   is, and `ResumeTailoringService` already reaches into `User.name()`
   because the profile cannot supply it.

## Contract

### `GET /api/profile/resume` → **`200 application/pdf`**

- Body: the stored original PDF bytes.
  `Content-Disposition: attachment; filename="resume-{userId}.pdf"`.
- **Auth: any authenticated principal** — `X-Bot-Token` (bot acts as the
  configured owner user) or `Bearer` JWT (self). Resolution goes through
  `CurrentUserService`; the file served is always the principal's **own**
  resume. No cross-user access, no server filesystem paths exposed — the
  bot downloads the artifact and stages it locally.
- `404`: no PDF stored for the user (never uploaded, or the file is gone
  on disk) — new domain exception `ResumeNotFoundException`, mapped by the
  GlobalExceptionHandler to a clean JSON error (no stack traces).
- `401`: unauthenticated (existing filter chain — `/api/profile/**` is
  already protected; no security config change).

### `GET /api/profile` → **`200`** (amended response)

- Adds `name` and `email` — the **account identity** from the `users` row.
- `email` is distinct from `contactEmail` (resume-extracted contact): both
  are present, resolved separately, per the #92 preview requirement.
- Read-only in this slice: no `PUT` for name/email — profile mutation with
  user confirmation belongs to slice (b)'s preview/commit flow.
- Blank-template behavior is preserved: when no `user_profiles` row exists
  the response still carries the account identity (the user row always
  exists for an authenticated principal).

## Design

- New out-port `ResumeStorage` (infrastructure-owned path convention):
  `Optional<byte[]> read(Long userId)` and `void write(Long userId, byte[]
  pdf)`. The adapter resolves `{upload-dir}/{userId}/resume.pdf` — the
  single owner of that convention. `ResumeUploadService` switched its
  direct `Files`/`transferTo` write to the port, so file access stops
  living in the application layer (absence is `Optional.empty()`, an
  expected state — not an error).
- New port-in `ResumeRetrievalUseCase` with
  `byte[] downloadResume(Long userId)` throwing `ResumeNotFoundException`
  when absent — fine-grained interface, house style.
- Account identity is resolved by a **separate read method** —
  `UserProfileUseCase.getAccountIdentity(userId)` returning the
  `AccountIdentity(name, email)` record (the service already holds
  `UserRepository`, the same port `ResumeTailoringService` uses). The
  separate method mirrors the issue requirement that identity is resolved
  apart from resume contact data, and leaves every existing `getProfile`
  contract and test untouched. `ProfileResponse` gains the two fields;
  `GET /api/profile` fills them, while `PUT`/`upload-resume` responses
  keep the same JSON shape with `null` identity — the preview/commit flow
  of slice (b) owns write-route identity.
- No new configuration: reuses `app.upload-dir`
  (`application.yaml:255`, `./uploads/resumes`).

## Non-goals (slice a)

- No preview/commit upload contract (slice b) and no apply-skill
  consumption of the backend profile (slice c) — until (c), the bot keeps
  staging its local copy; this endpoint only makes the backend copy
  retrievable.
- No file metadata columns (path/size/checksum/uploaded-at) — derivation
  by convention stays; staleness detection arrives with (b)/(c).
- No update path for account name/email; no per-user bot delegation,
  scopes, or audit trail — the single-owner bot model is unchanged.
- No tailored-resume persistence (`tailorResume` stays in-memory bytes).
- No README/`docs/bot-onboarding.md` changes — the manual `cp` removal
  happens with (c), when the end-to-end flow exists.

## Supersedes / drift noted

- `resume-upload.md:163` lists "Resume download / retrieval endpoint" as
  out of scope — **superseded by this spec**.
- `user-profile.md` DTO section (lines 56-71) is stale vs code (contact
  fields and preferences exist since profile-autofill); full refresh of
  that spec is tracked separately — this slice only adds the identity
  delta.
- `mcp-apply-loop.md` `resume_path` (bot-host local file) remains until
  slice (c); the two-artifact problem is documented there.

## Testing plan (RED before GREEN)

- `application` (Mockito — identity tests live in the existing
  `unit/application/UserProfileServiceTest`):
  `getAccountIdentity_whenUserExists_shouldReturnNameAndEmail`,
  `getAccountIdentity_whenUserMissing_shouldThrowUserNotFound`.
- `web` (`@WebMvcTest`, allowed by the house test table):
  `getResume_whenPdfStored_shouldReturn200WithPdf`,
  `getResume_whenNoPdf_shouldReturn404`,
  `getProfile_whenQueried_shouldCarryAccountIdentity` (blank profile row
  still returns 200; account identity distinct from `contactEmail`).
- `infrastructure` (plain JUnit, temp dir):
  `ResumeStorageAdapter` read/write round-trip.
- Existing upload tests must stay green (the write path moves behind the
  port without behavior change).
