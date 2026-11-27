# ADR 001: Keep multiple interfaces (Bot, TUI, Web)

## Status

Superseded by ADR 003 (self-hosted single-user, bot-only)

## Date

2026-09-05

## Context

Job Hunter started with the TUI (Rust) as the main interface. The bot (Hermes)
is now the primary interface via chat. In the future it may evolve into a
webapp. We decided to keep all interfaces.

## Decision

Keep:

- Bot (Hermes) — primary (and only) interface, self-hosted
- REST API + X-Bot-Token — used by the bot

## Consequences

- JWT removed; X-Bot-Token is the only auth (ADR 003)
- CLI removed from the public repo (ADR 002)
- Whenever web ships, auth is already solved
- Maintenance cost is zero (config in application.yaml)

## References

- docs/specs/bot-company-enrichment.md
- src/main/java/com/juanperuzzo/job_hunter/infrastructure/security/ (`BotTokenFilter`, `X-Bot-Token` only — JWT removed)
- docs/adr/002-cli-legacy.md (CLI/TUI moved out of the public repo)
