# ADR 003: Self-hosted single-user (bot-only)

## Status

Accepted

## Date

2026-11-26

## Context

Job Hunter evolved through several interface and deployment models:

1. **Multi-user SaaS-ish start** — PostgreSQL, JWT auth, Rust CLI/TUI, multi-interface (ADR 001). Designed for multiple users sharing one backend.
2. **Local persistence** — migrated from PostgreSQL to embedded SQLite (ADR not numbered, `docs/specs/sqlite-local-persistence.md`). Removed the need for a database server; the backend runs from a single file.
3. **Bot-first** — the Hermes bot became the primary interface (ADR 002); the CLI was marked legacy and then removed.
4. **Single operator** — the only real user is the owner. Multi-user auth (JWT, `users` table, `userId` scoping) carried cost with no benefit: more code, more tests, more migration complexity, and a larger attack surface for a personal tool.

The product is still **cloneable by anyone**: a new operator runs the backend locally, sets `bot.service.api-key`, and uses the Hermes bot. What changed is that the *default deployment model* is now single-tenant and self-hosted, not multi-tenant.

## Decision

- The public repo targets **self-hosted, single-user, bot-only**.
- **Multi-user auth is removed**: no JWT, no `users` table, no `userId` scoping. The only authentication is the static `X-Bot-Token` (service token), **required**: a blank `bot.service.api-key` fails fast at startup and the Spring context does not start (same contract as `HERMES_API_KEY`).
- **The CLI is removed** (ADR 002). The only interface is the Hermes bot; the REST API is an internal contract between the bot and the backend.
- **SQLite remains the only database** — no external server, no Docker for the DB.
- **The private clone** keeps the full multi-user + CLI version for the owner's own use; the public repo diverges permanently on auth and interface.
- **Anyone can still clone and run** the public repo: the backend is self-contained (SQLite + Flyway), and the bot onboarding (`docs/bot-onboarding.md`) covers setup from a fresh clone.

## Consequences

- Simpler codebase: no auth layer, no user management, no multi-tenancy scoping in repositories/services.
- Smaller test surface (net test deletion relative to the multi-user version).
- Migration V13 rebuilds tables without `user_id` and drops `users`; it auto-selects a sole legacy user, uses `BOT_SERVICE_OWNER_USER_ID` when several exist, and fails rather than selecting an arbitrary account.
- The bot's `X-Bot-Token` is the only gate; if `bot.service.api-key` is blank, startup fails fast (`IllegalStateException`) — there is no open mode.
- Contributors adding features target the bot/REST API, not a TUI or webapp.
- The private clone and public repo will diverge further over time; changes are not automatically portable between them.

## References

- ADR 001: docs/adr/001-multi-interface.md (superseded in part)
- ADR 002: docs/adr/002-cli-legacy.md
- Spec: docs/specs/self-hosted-bot-only.md
- Issue: #171
