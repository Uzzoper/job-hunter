# Spec: CLI/TUI Application (Rust)

> **Layer:** `cli` (separate Rust crate)  
> **Implementation:** `cli/src/`  
> **Binary:** `jh-cli`  
> **Stack:** Rust 2024, Clap, Ratatui, Crossterm, Reqwest, Tokio, Rusqlite

---

## Context

The CLI provides two interaction modes for the Job Hunter Spring Boot backend:

| Mode | Trigger | Description |
|------|---------|-------------|
| **TUI** (default) | No subcommand or `-T`/`--tui` | Interactive terminal UI with job listing, detail view, auth, profile |
| **Batch** | Any subcommand (`list`, `fetch`, `analyze`, etc.) | Non-interactive commands for scripting/CI |

Both modes share the same API client, config, and SQLite cache.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        jh-cli (Rust)                            │
├─────────────────────────────────────────────────────────────────┤
│  Cli (Clap)          │  TUI (Ratatui/Crossterm)  │  Batch       │
│  ──────────────      │  ──────────────────────  │  ─────────    │
│  • Commands          │  • App (state machine)   │  • Commands  │
│  • Args/Flags        │  • Screens (Auth/JobList │  • Output    │
│  • Config loading    │    /JobDetail/Profile)   │    formats   │
└─────────┬────────────┴─────────────┬───────────┴──────┬─────────┘
          │                           │                  │
          ▼                           ▼                  ▼
    ┌───────────────┐         ┌─────────────────┐ ┌──────────────┐
    │ ApiClient     │         │ CacheManager    │ │ Config       │
    │ (Reqwest)     │◀───────▶│ (Rusqlite)      │ │ (TOML)       │
    └───────┬───────┘         └─────────────────┘ └──────────────┘
            │
            ▼ HTTP/JSON
    ┌───────────────────┐
    │ Spring Boot API   │
    │ (localhost:8080)  │
    └───────────────────┘
```

### Package Structure

```
cli/src/
├── lib.rs              ← Clap CLI definition, public exports
├── main.rs             ← Entry point (TUI vs Batch dispatch)
├── domain.rs           ← All DTOs + business logic (serde, enums, helpers)
├── api.rs              ← ApiClient + request/response handling
├── config.rs           ← ConfigManager (TOML + defaults)
├── cache.rs            ← CacheManager (Rusqlite + schema)
├── error.rs            ← Error types (thiserror)
├── util.rs             ← Helpers (clipboard, open URL, date fmt)
├── batch/
│   ├── mod.rs          ← Command dispatcher
│   ├── auth.rs         ← login/register/logout
│   ├── jobs.rs         ← list/detail/fetch/export
│   ├── analyze.rs      ← AI analysis trigger
│   └── profile.rs      ← profile show/edit
└── tui/
    ├── mod.rs          ← run() entry point
    ├── app.rs          ← App state machine + event loop
    ├── theme.rs        ← Color theme (green/cyan dark)
    ├── auth_screen.rs  ← Login/register form
    ├── job_list_screen.rs   ← List + filters + search
    ├── job_detail_screen.rs ← Detail + actions (analyze/email/open)
    └── profile_screen.rs    ← Profile editor (resume/skills/contact/tone/projects)
```

---

## CLI Commands (Batch Mode)

### Global Options
| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--api-url` | `-u` | `http://localhost:8080` | Backend base URL |
| `--config` | `-c` | `~/.config/job-hunter/config.toml` | Config file path |
| `--token` | `-t` | (from config) | JWT token (overrides config) |
| `--tui` | `-T` | false | Force TUI mode |

### `auth` — Authentication
```
jh-cli auth login <email> <password>
jh-cli auth register <name> <email> <password>
jh-cli auth logout
```
- Stores token in config file on success
- `logout` clears stored token
- **Password validation**: minimum 6 characters
- **Email validation**: must contain `@` and a domain part

### `list` — List Jobs
```
jh-cli list [--keyword <str>] [--min-score <0-100>] [--source <gupy|linkedin|infojobs>] [--csv|--json] [--offline] [--refresh]
```
| Flag | Description |
|------|-------------|
| `--keyword` | Filter title/company (case-insensitive) |
| `--min-score` | Filter by AI match score (triggers analysis if missing) |
| `--source` | Filter by job source |
| `--csv` / `--json` | Output format (default: table) |
| `--offline` | Use cache only, no API call |
| `--refresh` | Force fetch from API, update cache |

### `detail` — Job Detail
```
jh-cli detail <job-id> [--json]
```

### `fetch` — Trigger Backend Scraping
```
jh-cli fetch [--source <gupy|linkedin|infojobs>]
```
- Calls `POST /api/jobs/fetch` or `POST /api/jobs/fetch/linkedin`

### `analyze` — AI Analysis
```
jh-cli analyze <job-id> [--json]
```
- Calls `POST /api/jobs/{id}/analyze`

### `email` — Email Drafts
```
jh-cli email show <job-id> [--json] [--copy]
jh-cli email generate <job-id>
jh-cli email approve <job-id>
jh-cli email send <job-id>
```
- `--copy` copies body to clipboard (arboard)
- `approve` changes draft status from `PENDING` → `APPROVED` (required before send)
- `send` sends the email via Resend API; returns with status `SENT` and `sent_at` timestamp
  *(update 2026-08: delivery now goes through the Hermes Agent bot — `hermes-agent-integration.md`; status behavior unchanged)*

### `profile` — User Profile
```
jh-cli profile show [--json]
jh-cli profile edit [--resume <text>] [--skills <csv>] [--tone <formal|casual|startup>]
                   [--phone <str>] [--contact-email <email>]
                   [--portfolio-url <url>] [--github-url <url>] [--linkedin-url <url>]
```
- Contact fields are optional; omitted flags keep the stored value
- Passing an empty string (e.g. `--phone ""`) clears the field on the backend
- Validation mirrors the backend: phone ≤ 30 chars; contact-email must be a valid email ≤ 255 chars; URLs ≤ 500 chars

### `export` — Export to CSV
```
jh-cli export <output-path> [--keyword <str>]
```

### `clear-cache` — Clear Local SQLite Cache
```
jh-cli clear-cache
```

---

## TUI Mode

### Screens & Navigation

```
┌─────────────────────────────────────────────────────────────┐
│ Job Hunter — Job Listings              ● Connected         │
├─────────────────────────────────────────────────────────────┤
│  Search: [_______________________]        │  Jobs (25)     │
│  ────────────────────────────────────────┼──────────────── │
│  ▸  Senior Rust Engineer        Acme Inc │  gupy    92%   │
│    Junior Java Developer        TechCo   │  linkedin 85%  │
│    Backend Engineer (Remote)    StartupX │  infojobs 78%  │
│  ────────────────────────────────────────┼──────────────── │
│  [↑/↓] Navigate  [Enter] Detail  [/] Search  [q] Quit     │
│  [f] Filter  [s] Seniority  [d] Dev-only [t] Apply-type   │
│  [o] Open URL  [r] Refresh  [p] Profile                   │
└─────────────────────────────────────────────────────────────┘
```

| Screen | Entry Key | Exit Key | Purpose |
|--------|-----------|----------|---------|
| **Auth** | (startup if no token) | Enter / q | Login / Register |
| **JobList** | default / `b` / Esc | `q` / `p` | List, filter, search, select |
| **JobDetail** | Enter on job | `b` / Esc | View detail, analyze, email, open URL |
| **Profile** | `p` from JobList | `b` / Esc | Edit resume, skills, tone |

### Keybindings (Global)
| Key | Action |
|-----|--------|
| `q` / `Q` | Quit (or dismiss error) |
| `Ctrl+C` | Force quit |
| `Esc` | Back / dismiss search / dismiss error |
| `Enter` | Confirm / open detail / login |
| `Tab` | (reserved) |
| `/` or `Ctrl+F` | Focus search (JobList) |

### JobList Keybindings
| Key | Action |
|-----|--------|
| `↑` / `k` / `j` / `↓` | Navigate |
| `Enter` | Open JobDetail |
| `/` / `Ctrl+F` | Focus search input |
| `s` / `S` | Cycle seniority filter (All → Junior → Pleno → Senior) |
| `d` / `D` | Toggle "dev roles only" filter |
| `t` / `T` | Cycle apply-type filter (All → Email → External → Unknown) |
| `f` / `F` | Trigger backend job scraping (see Fetch Trigger below) |
| `o` / `O` | Open selected job URL in browser |
| `r` / `R` | Refresh (force API fetch) |
| `p` / `P` | Open Profile screen |

#### Fetch Trigger (`f`)
Pressing plain `f` (or `F`, no modifiers) in the JobList screen triggers backend
scraping via `POST /api/jobs/fetch`. It mirrors the Profile screen's resume-upload
pattern (`start_upload` / `pending_upload` / `finish_upload`):

- **Trigger**: `f` sets a `fetch_in_progress` flag on the screen; the actual HTTP
  call happens in the main loop, which draws one frame showing status, then awaits
  inline. The UI waits while scraping runs.
- **Re-trigger guard**: pressing `f` while a fetch is already in progress is a no-op.
- **Timeout**: the request uses a per-request timeout of 10 minutes (the global
  client timeout of 30s is too short — scraping all providers takes 40–90s).
- **On success**: the job list reloads through the same path used by `r`
  (refresh), and a "Fetch completed" toast is shown.
- **On failure**: an error toast with the reason is shown; the list stays as-is.
- `Ctrl+F` remains search focus — only unmodified `f`/`F` triggers the scrape.

### JobDetail Keybindings
| Key | Action |
|-----|--------|
| `b` / `B` / `Esc` | Back to JobList |
| `a` / `A` | Trigger AI analysis (`POST /analyze`) |
| `e` / `E` | Generate email draft (`POST /email`) |
| `p` / `P` | Approve email draft (`POST /email/approve`) |
| `s` / `S` | Send email (`POST /send`) |
| `c` / `C` | Copy email to clipboard |
| `o` / `O` | Open job URL in browser |
| `↑` / `↓` | Scroll description |

### Profile Screen
- Editable fields: **Resume** (multiline), **Skills** (comma-separated), **Tone** (cycle: Formal → Casual → Startup), **Projects** (list)
- `Enter` in Resume field = insert newline
- `Ctrl+S` = Save (`PUT /api/profile`)
- `Esc` / `b` = Back without saving
- Bracketed paste supported for resume/skills
- **Contact group**: 5 optional single-line inputs rendered together in one bordered block below Skills: Phone, Contact Email, Portfolio URL, GitHub URL, LinkedIn URL
  - Empty input = "not set"; sent as absent on `PUT /api/profile`
  - Focus cycle (Tab/↑↓): Resume → Skills → Phone → Contact Email → Portfolio URL → GitHub URL → LinkedIn URL → Tone → Projects
  - Client-side validation on save mirrors backend limits (phone ≤ 30, email format ≤ 255, URLs ≤ 500)
  - Contact values are shown in view mode too (same block, read-only)
- **Projects section**: displayed as a scrollable list below tone. Each project shows `name` + first line of `description` + tech stack
- `n` = Add new project (opens project edit popup with Name, Description, Tech Stack fields)
- `d` = Delete selected project (with confirmation)
- `Enter` on a project = Edit project fields
- **Project edit popup**: 3 fields (Name, Description, Tech Stack as comma-separated), `Tab` to navigate fields, `Ctrl+S` to confirm, `Esc` to cancel

### Auth Screen
- Tab between Email / Password
- `Enter` = Submit (login or register depending on mode)
- `Esc` = Quit app
- Shows error popup on failure (Enter to dismiss)

---

## Data Models (domain.rs)

### Core DTOs (match Spring Boot API)

| Struct | Source | Key Fields |
|--------|--------|------------|
| `JobResponse` | `GET /api/jobs` | `id`, `title`, `company`, `url`, `description`, `posted_at`, `source` |
| `JobDetailResponse` | `GET /api/jobs/{id}` | `JobResponse` + `match_score`, `analysis_json` |
| `AuthResponse` | `POST /auth/*` | `token`, `user_id`, `name`, `email` |
| `ProfileResponse` | `GET /api/profile` | `id?`, `user_id`, `resume_text`, `skills[]`, `tone`, `projects[]` (each: `name`, `description`, `tech_stack[]`), optional contact fields: `phone?`, `contactEmail?`, `portfolioUrl?`, `githubUrl?`, `linkedinUrl?` |
| `EmailDraftResponse` | `GET/POST /api/jobs/{id}/email` | `id`, `job_id`, `subject`, `body`, `status`, `generated_at`, `sent_at?` |
| `FetchResponse` | `POST /api/jobs/fetch` | `message` |
| `ErrorResponse` | Error responses | `timestamp`, `status`, `error`, `message` |

### Enums (with serialization)

```rust
enum CompanyTone { Formal, Casual, Startup }  // "FORMAL" | "CASUAL" | "STARTUP"
enum EmailStatus { Pending, Approved, Sent } // "PENDING" | "APPROVED" | "SENT"
enum ApplyType { EmailAvailable, ExternalApply, Unknown }
enum SeniorityLevel { Junior, Pleno, Senior, Unknown }
```

### Derived Logic (pure functions, tested)

| Function | Input | Output | Rules |
|----------|-------|--------|-------|
| `ApplyType::from_description(desc)` | `&str` | `ApplyType` | Empty → `ExternalApply`; <20 chars/whitespace → `Unknown`; else `EmailAvailable` |
| `SeniorityLevel::from_title(title)` | `&str` | `SeniorityLevel` | Keywords: jr/junior/estagiário/trainee → Junior; pleno/mid → Pleno; sr/senior/lead/specialist → Senior; else Unknown. Senior > Pleno > Junior precedence. |
| `is_dev_role(title)` | `&str` | `bool` | Positive: dev/engineer/programador/desenvolvedor/backend/frontend/data/devops/qa/mobile. Negative: designer/pm/product/suporte/marketing/sales/rh/finance. Negative overrides. |

### Cache Model

```rust
struct CachedJob {
    id, title, company, url, description, posted_at, source,
    match_score: Option<i32>,
    analysis_json: Option<String>,
    email_subject: Option<String>,
    email_body: Option<String>,
    email_status: Option<String>,
    cached_at: NaiveDateTime,
}
```

---

## Configuration

### Config File (`~/.config/job-hunter/config.toml`)
```toml
base_url = "http://localhost:8080"
token = "eyJhbGciOi..."   # optional, set after login
cache_ttl_hours = 24
```

### Precedence
`CLI flag` > `Config file` > `Default`

---

## Caching Strategy (SQLite)

### Cache File
SQLite database at `~/.config/job-hunter/cache.db` (default) or custom path.

### Schema
```sql
CREATE TABLE jobs (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    company TEXT NOT NULL,
    url TEXT NOT NULL UNIQUE,
    description TEXT,
    posted_at TEXT NOT NULL,      -- ISO date
    source TEXT NOT NULL,
    match_score INTEGER,
    analysis_json TEXT,
    email_subject TEXT,
    email_body TEXT,
    email_status TEXT,
    cached_at TEXT NOT NULL       -- ISO datetime
);
CREATE INDEX idx_jobs_source ON jobs(source);
CREATE INDEX idx_jobs_cached_at ON jobs(cached_at);
```

### Behavior
| Operation | Cache Action |
|-----------|--------------|
| `GET /api/jobs` (list) | **Phase 1**: Load all from cache instantly → render. **Phase 2**: Fetch API → upsert → re-render. |
| `GET /api/jobs/{id}` | Check cache first; if miss or stale → API → upsert. |
| `POST /api/jobs/fetch` | Invalidate (delete all) → fetch → repopulate. |
| `analyze` / `email` | Update `analysis_json`, `email_*` columns for that `job_id`. |
| TTL | Configurable (`cache_ttl_hours`, default 24h). `is_stale()` compares `cached_at`. |

### Batch Queries
- `get_all_jobs(filter)` — single query with optional `WHERE source = ?`
- `get_all_scores()` — returns `HashMap<i64, u8>` for instant score badge rendering

---

## Error Handling

| Layer | Strategy |
|-------|----------|
| `ApiClient` | `Result<T, CliError>` — wraps `reqwest::Error`, HTTP status ≥ 400 → mapped to `ApiError` variant with response body |
| `CacheManager` | `Result<T, CliError>` — wraps `rusqlite::Error` as `CliError::Cache(String)` |
| `ConfigManager` | `anyhow::Result<Config>` — wraps `toml`, `std::io`, and missing-directory errors via `anyhow::Context` |
| TUI | Errors caught in `handle_event` → `App.set_error(msg)` → popup |
| Batch | Errors printed to stderr, exit code 1 |

### Error Types (`error.rs`)

```rust
pub enum ApiError {
    BadRequest(String),       // HTTP 400
    Unauthorized(String),     // HTTP 401
    NotFound(String),         // HTTP 404
    Conflict(String),         // HTTP 409
    BadGateway(String),       // HTTP 502
    ServerError(String),      // HTTP 5xx (excluding 502)
    HttpError(reqwest::Error), // transport-level failure
    DeserializeError(String), // JSON parse failure
}
```

```rust
pub enum CliError {
    Api(ApiError),           // wraps ApiError
    Config(String),          // config file read/parse/write
    Cache(String),           // SQLite operation
    Network(String),         // connectivity (connection refused, timeout, DNS)
    Clipboard(String),       // system clipboard (arboard)
    Io(std::io::Error),      // file I/O
    Internal(String),        // unexpected internal errors
}
```

---

## Testing

### Unit Tests (`domain.rs` inline)
- Serde round-trips for all DTOs
- `ApplyType::from_description` edge cases (empty, whitespace, short, boundary)
- `SeniorityLevel::from_title` (PT/EN keywords, precedence, unknown)
- `is_dev_role` (positive/negative/override cases)

### Integration Tests (`tests/integration_tests.rs`)
- Full CLI command execution via `assert_cmd`
- Mock HTTP server (`httpmock`) for API responses
- Temp SQLite DB per test

### TUI Tests
| File | Coverage |
|------|----------|
| `tui/theme_test.rs` | Theme color constants |
| `tui/auth_screen_test.rs` | Auth form input/navigation |
| `tui/app_test.rs` | App state transitions |
| `tui/app_integration_test.rs` | Full TUI flow with mocked API |

Run: `cargo test` (unit + integration)

---

## Build & Release

```bash
# Dev build
cargo build

# Release (optimized, stripped)
cargo build --release
# Binary at: cli/target/release/jh-cli
```

### Docker (optional)
```dockerfile
FROM rust:1.85 AS builder
WORKDIR /app
COPY cli/ ./cli/
RUN cargo build --release -p jh-cli

FROM debian:bookworm-slim
COPY --from=builder /app/cli/target/release/jh-cli /usr/local/bin/
ENTRYPOINT ["jh-cli"]
```

---

## Out of Scope

- No auto-refresh timer in TUI (manual `r` only)
- No plugin system
- No multi-user config (single config file per user)
- No web-based UI (Spring Boot serves that separately)
- No built-in scheduler (use cron/systemd for `jh-cli fetch`)

---

## Agent Prompt (OpenCode)

```
Read the spec at docs/specs/cli-tui-spec.md.

The CLI is a Rust crate (cli/) with two modes:
- TUI (default): Ratatui/Crossterm interactive app with 4 screens (Auth, JobList, JobDetail, Profile)
- Batch: Clap subcommands for scripting (list, fetch, analyze, email, profile, export, clear-cache)

Key files:
- cli/src/lib.rs       → CLI definition (Clap), public exports
- cli/src/main.rs      → Entry point, dispatches TUI vs Batch
- cli/src/domain.rs    → ALL DTOs, enums, pure logic (ApplyType, SeniorityLevel, is_dev_role), inline tests
- cli/src/api.rs       → ApiClient (Reqwest), error handling
- cli/src/cache.rs     → CacheManager (Rusqlite), schema, batch queries
- cli/src/tui/app.rs   → App state machine, event loop, keybindings, screen routing
- cli/src/tui/*_screen.rs → Screen render + input handlers

Architecture: Clean-ish separation — domain is pure Rust (no framework deps), api/cache/config are infrastructure, tui/batch are delivery mechanisms.

When modifying:
- Domain logic changes → update inline tests in domain.rs
- New CLI command → add to lib.rs Command enum + batch/*.rs handler
- New TUI screen → add module in tui/, wire in app.rs state machine
- New API field → update DTO in domain.rs + cache schema if persisted
- Run `cargo test` after changes
```