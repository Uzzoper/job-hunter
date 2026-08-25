# Spec: CLI auth UX fixes (Tab navigation, config path, auto-skip login)

> **Layer:** CLI (`cli/src`) — no backend changes
> **Implementation files:** `cli/src/tui/auth_screen.rs`, `cli/src/tui/app.rs`, `cli/src/batch/mod.rs`, `cli/src/batch/auth.rs`, `cli/src/config.rs` (as needed)
> **Corresponding tests:** `cli/tests/integration_tests.rs`, `cli/src/tui/app_integration_test.rs`, unit tests in touched modules

---

## Goal

Fix three friction points found in smoke testing:
1. Tab toggles Login↔Register instead of moving between fields
2. `-c/--config` is ignored by `auth register/login` (token always persists to the default path)
3. TUI always opens on the auth screen even when a valid token is saved

---

## Expected behavior

### Scenario 1: Tab moves focus between auth fields
- **GIVEN** the auth screen in login mode (email, password fields)
- **WHEN** the user presses `Tab`
- **THEN** focus moves to the next field, wrapping around
- **AND** `Shift+Tab` moves to the previous field
- **AND** `Tab` no longer toggles Login↔Register mode; the mode toggle remains on its existing dedicated key

### Scenario 2: `-c/--config` is honored by auth commands
- **GIVEN** `jh-cli -c /tmp/alt-config.toml auth login <email> <password>` with a successful login
- **WHEN** the token is persisted
- **THEN** it is written to `/tmp/alt-config.toml`, not the default config path
- **AND** the same applies to `auth register` and `auth logout`
- **AND** the default path (`~/.config/job-hunter/config.toml`) keeps `0600` permissions

### Scenario 3: `JH_TOKEN` env override
- **GIVEN** `JH_TOKEN` is set in the environment
- **WHEN** any authenticated CLI command or the TUI runs
- **THEN** the env token is used for requests
- **AND** it is never written to the config file (read-only override)

### Scenario 4: TUI skips the auth screen with a valid saved token
- **GIVEN** a token saved in the config (or `JH_TOKEN` set)
- **WHEN** the TUI starts
- **THEN** it validates the token with a cheap authenticated call (e.g. `GET /api/profile`)
- **AND** on success it lands directly on the job list screen
- **AND** on `401` (expired/invalid) it falls back to the auth screen
- **AND** on network error it falls back to the auth screen showing the connection error
- **AND** with no saved token it opens on the auth screen as today

---

## Business rules

- **Rule 1 — token storage stays file-based.** Token lives in the config file (`0600`).
  Rationale: 24h JWT expiry, local-first/headless compatibility (no dbus/keyring dependency),
  zero new dependencies. OS keyring is a documented future enhancement, not part of this spec.
- **Rule 2 — `JH_TOKEN` is read-only.** Env override never persists; config file content is
  never modified because of it.
- **Rule 3 — config path plumbing.** The `-c/--config` value flows into every auth command
  (register/login/logout) and into TUI startup; `config::load(None)` hardcodes are removed.
- **Rule 4 — validation is lazy, not blocking.** Startup token validation uses one cheap call;
  no token refresh mechanism is introduced (24h expiry → re-login is acceptable).

---

## Error cases

| Situation | Behavior |
|---|---|
| Saved token expired (401 on validation call) | Auth screen opens, no error spam |
| Backend unreachable at startup | Auth screen opens with connection error message |
| `JH_TOKEN` set but invalid | Commands fail with 401 from backend; nothing persisted |
| Custom config path not writable | Auth commands fail with a clear IO error |

---

## Out of scope

- OS keyring integration (future enhancement)
- Token refresh / remember-me
- Changing the default config location or format
- Backend auth changes

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/cli-auth-ux-fixes.md.

Step 1 — write the failing tests (RED): Tab focus navigation on the auth
screen, -c config persistence for auth commands, JH_TOKEN override,
TUI skip-to-job-list with valid token (and fallbacks).
Do not change production code yet.

Step 2 — wait for confirmation before implementing.
```
