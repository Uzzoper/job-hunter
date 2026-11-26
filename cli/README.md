# jh-cli — Legacy

> **Legacy interface.** Daily use of Job Hunter goes through the **Hermes bot**
> (`jobhunter-bot` profile). This CLI/TUI is kept for debug, manual JWT access
> and scripted batch operations. See ADR 002 (`docs/adr/002-cli-legacy.md`) and
> the main README.

## Build

```bash
cargo build --release
./target/release/jh-cli --help
```

## Modes

| Mode | Trigger | Description |
|------|---------|-------------|
| TUI (default) | No subcommand or `-T`/`--tui` | Interactive terminal UI |
| Batch | Any subcommand | Non-interactive commands for scripting/CI |

Batch commands: `docs/cli.md` (repo root). Full spec: `docs/specs/cli-tui-spec.md`.