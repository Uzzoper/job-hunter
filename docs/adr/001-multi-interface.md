# ADR 001: Keep multiple interfaces (Bot, TUI, Web)

## Status

Superseded by ADR 002 (CLI removed from the public repo; bot is the only interface)

## Date

2026-09-05

## Context

Job Hunter started with the TUI (Rust) as the main interface. The bot (Hermes)
is now the primary interface via chat. In the future it may evolve into a
webapp. We decided to keep all interfaces.

## Decision

Keep:

- Bot (Hermes) — primary interface, self-hosted
- REST API + X-Bot-Token — used by the bot (JWT retained for now, to be removed in the self-hosted-only phase)

## Consequences

- JWT stays in the backend even without direct use by the bot
- TUI remains in the repo (living, tested code)
- Whenever web ships, auth is already solved
- Maintenance cost is zero (config in application.yaml)

## References

- docs/specs/bot-company-enrichment.md
- src/main/java/com/juanperuzzo/job_hunter/infrastructure/security/ (JWT)
- cli/ (TUI in Rust)
