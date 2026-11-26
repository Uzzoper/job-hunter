# ADR 002: CLI removed; Hermes bot is the only interface

## Status

Accepted (supersedes ADR 001's TUI provision)

## Date

2026-11-25

## Context

Job Hunter started with the Rust CLI/TUI (`cli/`) as the main interface. The
Hermes bot (`jobhunter-bot` profile, systemd user service) is now the primary
interface: it lists, analyzes, drafts and applies for jobs via chat, with
confirmation gates and verified submits. The CLI is no longer maintained in
the public repo; the private clone retains it for debug/manual use.

## Decision

- The **Hermes bot is the primary (and only) interface** in the public repo.
  Portfolio reviewers and new users should start at `docs/bot-onboarding.md`.
- The **Rust CLI/TUI is removed** from the public repo (commit 428f373).
  The private clone retains it for debug/manual use.
- No new features are added to the CLI. Existing CLI code lives only in the
  private clone.
- The README demo GIF illustrates the CLI workflow (legacy); a bot-chat demo
  is impractical (listing takes minutes and exposes real data), so the
  illustrative GIF + converter script (`scripts/make-bot-gif.sh`) are the
  accepted substitute.

## Consequences

- README positions the bot first; no CLI section remains.
- CI no longer builds or tests the CLI.
- Contributors adding interface features target the bot/REST API.
- The private clone and public repo diverge on interface (see ADR 003).

## References

- ADR 001: docs/adr/001-multi-interface.md
- ADR 003: docs/adr/003-self-hosted-single-user.md
- README.md (## Hermes bot (primary interface))
- docs/bot-onboarding.md
