# Spec: Filter jobs by contact email presence in TUI

> **Layer:** `cli` (Rust TUI)
> **Depends on:** backend `contactEmail` persistence fix (done) and `?hasEmail` API filter (done)
> **Tests:** existing `cli/src/tui/app_integration_test.rs`

---

## Expected behavior

### Scenario 1: no email filter (default)
- **GIVEN** the job list screen
- **WHEN** no email filter is active
- **THEN** all jobs are displayed (as before)

### Scenario 2: enable email filter
- **GIVEN** the job list screen
- **WHEN** the user activates the email filter (hotkey, e.g. `e`)
- **THEN** only jobs with a non-null `contactEmail` are shown

### Scenario 3: filter state displayed
- **GIVEN** the email filter is active
- **WHEN** viewing the job list stats line
- **THEN** a visual indicator (e.g. `[EMAIL ONLY]`) appears alongside other active filters

---

## Design

### Data
- `JobResponse` in `cli/src/domain.rs` gains a `contact_email: Option<String>` field
- The existing `job_list_screen` gets a new `has_email_filter: bool` field

### Filter behavior
- Toggle on/off via a keybinding (suggestion: `e`)
- Works as an additional AND clause in `apply_filters()`:
  - If `has_email_filter == true`, filter `|job| job.contact_email.is_some()`
- Active state is displayed in the stats line alongside `ApplyTypeFilter` / `SeniorityFilter`
- Default state: `false` (no filtering)

### UI integration
- Stats line shows `[EMAIL ONLY]` when active (same style as other filter indicators)
- Keybinding hint shown in the controls bar at the bottom

---

## Files to change

| File | Change |
|---|---|
| `cli/src/domain.rs` | Add `contact_email: Option<String>` to `JobResponse` |
| `cli/src/tui/job_list_screen.rs` | Add `has_email_filter: bool`, wire into `apply_filters()`, display in stats |
| `cli/src/tui/app.rs` | Handle the new keybinding in the event loop |

---

## Out of scope

- Sending `?hasEmail=true` to the API (client-side filter only — no backend round-trip needed)
- Adding a dedicated filter UI widget (reuses existing toggle pattern)
