# ADR 002: CLI is legacy; Hermes bot is the primary interface

## Status

Accepted

## Date

2026-11-25

## Context

Job Hunter started with the Rust CLI/TUI (`cli/`) as the main interface. The
Hermes bot (`jobhunter-bot` profile, systemd user service) is now the primary
interface: it lists, analyzes, drafts and applies for jobs via chat, with
confirmation gates and verified submits. The CLI remains useful for debug,
manual JWT access and scripted batch operations, but it is no longer the
recommended daily path (see ADR 001).

## Decision

- The **Hermes bot is the primary interface**. Portfolio reviewers and new
  users should start at `docs/bot-onboarding.md`.
- The **Rust CLI/TUI is legacy**: kept in the repo, built and tested, but
  marked legacy in the README and in `cli/README.md`. It is supported only
  for debug, manual JWT and scripting.
- No new features are added to the CLI unless they are also exposed via the
  bot or the REST API. Existing CLI commands stay (no removal) to avoid
  breaking scripts.
- The README demo GIF illustrates the CLI workflow (legacy); a bot-chat demo
  is impractical (listing takes minutes and exposes real data), so the
  illustrative GIF + converter script (`scripts/make-bot-gif.sh`) are the
  accepted substitute.

## Consequences

- README positions the bot first; CLI section is explicitly "legacy".
- `cli/README.md` opens with a legacy notice.
- CI keeps building and testing the CLI (no removal).
- Contributors adding interface features target the bot/REST API, not the TUI.

## References

- ADR 001: docs/adr/001-multi-interface.md
- README.md (## Hermes bot (primary interface), ## CLI / TUI (legacy))
- docs/bot-onboarding.md
- docs/cli.md