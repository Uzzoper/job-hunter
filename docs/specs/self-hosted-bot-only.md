# Spec: Self-hosted bot-only (remove CLI and multi-user auth)

> **Layer:** all layers (domain, application, infrastructure, web)
> **Status:** proposed — awaiting implementation
> **Depends on:** ADR 002 (CLI removed), bot service token (X-Bot-Token)
> **Non-goals:** multi-tenancy, webapp, new features

---

## Context

The public repo is now **self-hosted, single-user, bot-only**. The private clone
keeps the full multi-user + CLI version. In this repo:

- The Rust CLI is removed (Phase 1, commit 428f373).
- The only interface is the Hermes bot (`jobhunter-bot` profile, systemd).
- The only authentication is the static `X-Bot-Token` (service token).
- There is exactly one operator (the owner). No user accounts, no JWT, no login.

This spec describes the removal of multi-user auth and the simplification to a
single-tenant backend.

## Expected behavior

### Phase 2a — Remove JWT and user management (backend)

- **AuthController** (`/api/auth/register`, `/api/auth/login`) is deleted.
- **JwtTokenFilter**, **JwtTokenService**, **SecurityConfig** (JWT-based) are deleted.
- **CurrentUserService** / **CurrentUserProvider** port is deleted; controllers
  no longer resolve a `userId`.
- **User** domain model and **users** table are removed (or kept as a single
  seeded row — TBD in migration).
- **UserProfile** loses `userId`; there is exactly one profile row. `findByUserId`
  becomes `find()` (single row).
- **Job**, **EmailDraft**, **JobAnalysis** lose `userId` (single tenant).
- **JobRepository**, **EmailDraftRepository**, **JobAnalysisRepository** queries
  drop the `userId` parameter.
- **Use cases** (`AnalyzeJobUseCase`, `GenerateEmailUseCase`, `SendEmailUseCase`,
  `UserProfileUseCase`, etc.) drop `userId` from their signatures.
- **Security:** only `BotServiceTokenFilter` remains (X-Bot-Token). All other
  requests are rejected (401) unless they carry a valid bot token. No JWT.
- **Flyway:** new migration drops `user_id` columns / `users` table. SQLite
  doesn't support DROP COLUMN before 3.35; use table rebuild (create new, copy,
  drop old, rename).

### Phase 2b — Simplify controllers and services

- `JobController` endpoints no longer take `userId`; use the single profile.
- `ProfileController` reads/writes the single profile.
- `AiAnalysisService`, `EmailGenerationService`, `ResumeUploadService`,
  `ResumeTailoringService` lose `userId` param.
- `BotMemorySyncService` / `BotMemoryStartupSync` simplified (no per-user memory).

### Phase 2c — Tests

- Remove auth-related tests (`AuthControllerTest`, `JwtTokenServiceTest`, etc.).
- Update all use-case tests to drop `userId` arguments.
- Update controller tests to drop `authenticateAs(...)` / JWT setup; keep
  `X-Bot-Token` header setup.
- Target: full suite green with fewer tests (net deletion).

### Phase 2d — Docs and config

- README: remove auth endpoints from API table; remove JWT from tech stack;
  remove `jwt.secret` from config example.
- `application.yaml`: remove `jwt:` block.
- AGENTS.md: update architecture (no auth layer), tech stack, roadmap.
- `docs/bot-onboarding.md`: remove JWT login step.

## Non-goals

- No multi-tenancy in this repo (lives in the private clone).
- No webapp / browser UI.
- No removal of the REST API itself (bot still uses it via X-Bot-Token).
- No change to scraping, AI, email sending, or the bot skills.

## Migration notes

- Existing databases with `users`/`user_id` columns: the migration rebuilds
  tables. Data is single-user (owner's), so no data loss for the owner.
- The private clone keeps the auth version; this repo diverges permanently.

## Open questions (owner)

1. Should `User`/`UserProfile` merge into a single `Profile` config table, or
   keep `user_profiles` with a fixed `id = 1`?
2. Should the API be fully open on localhost (no token) when
   `bot.service.token` is unset, or fail fast (like `HERMES_API_KEY`)?
3. Keep `ProfileController` or fold profile into a config file?
EOF
echo "created"