# CLI / TUI — batch commands

The `jh-cli` Rust binary supports non-interactive batch commands for scripting
and CI. Start the TUI (default mode) with no subcommand, or use any subcommand
for batch mode.

## Authentication

| Command | Description |
|---------|-------------|
| `jh-cli auth login <email> [password]` | Authenticate and store token |
| `jh-cli auth register <name> <email> [password]` | Create a new account |
| `jh-cli auth logout` | Clear stored credentials |

## Jobs

| Command | Description |
|---------|-------------|
| `jh-cli list [--keyword] [--min-score] [--source] [--csv\|--json]` | List jobs with filters and format flags |
| `jh-cli detail <id> [--json]` | Show full job detail |
| `jh-cli fetch [source]` | Trigger backend scraping (all providers or a specific one) |
| `jh-cli analyze <job-id> [--json]` | Trigger AI analysis for a job |

## Email

| Command | Description |
|---------|-------------|
| `jh-cli email show <job-id> [--json] [--copy]` | View generated email draft (optionally copy to clipboard) |
| `jh-cli email generate <job-id>` | Generate a new email draft |
| `jh-cli email send <job-id>` | Send email for a job |
| `jh-cli email approve <job-id>` | Approve a pending draft for auto-send |

## Profile

| Command | Description |
|---------|-------------|
| `jh-cli profile show [--json]` | View current profile |
| `jh-cli profile edit [--resume] [--skills] [--tone]` | Update profile fields |
| `jh-cli profile upload <path>` | Upload PDF resume — AI extracts skills & projects |

## Utility

| Command | Description |
|---------|-------------|
| `jh-cli export <output> [--keyword]` | Export jobs to a CSV file |
| `jh-cli clear-cache` | Clear local SQLite cache |

> Full spec at [`docs/specs/cli-tui-spec.md`](specs/cli-tui-spec.md)