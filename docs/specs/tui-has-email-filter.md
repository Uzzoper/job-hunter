# Spec: Filter jobs by contact email presence in TUI (`t` keybinding)

> **Layer:** `cli` (Rust TUI)
> **Depends on:** backend `contactEmail` persistence fix (done) and `?hasEmail` API filter (done)
> **Tests:** existing `cli/src/tui/app_integration_test.rs`

---

## Expected behavior

### Scenario 1: no email filter (default)
- **GIVEN** the job list screen
- **WHEN** no email filter is active (`ApplyTypeFilter::All`)
- **THEN** all jobs are displayed (as before)

### Scenario 2: enable email filter via ApplyTypeFilter cycle
- **GIVEN** the job list screen
- **WHEN** the user presses `t` repeatedly until `ApplyTypeFilter::EmailAvailable` is active
- **THEN** only jobs with a non-null `contactEmail` are shown (filtered via `job.contact_email.is_some()`)

### Scenario 3: filter state displayed
- **GIVEN** the email filter is active (`ApplyTypeFilter::EmailAvailable`)
- **WHEN** viewing the job list stats line
- **THEN** a `📧 EMAIL` indicator appears in the stats line alongside other active filters

---

## Design

### Data
- `JobResponse` in `cli/src/domain.rs` already has `contact_email: Option<String>` field
- No new `has_email_filter` field — the existing `ApplyTypeFilter` enum gained `EmailAvailable`, cycled via `t`

### Filter behavior
- `t` key cycles `ApplyTypeFilter`: `All → ExternalApply → EmailAvailable → Unknown → All …`
- When `ApplyTypeFilter::EmailAvailable` is active, filter `|job| job.contact_email.is_some()` is applied in `apply_filters()`
- Active state shows `📧 EMAIL` in the stats line alongside `SeniorityFilter`
- Default state: `ApplyTypeFilter::All` (no filtering)

### UI integration
- Stats line shows `📧 EMAIL` when the filter is active (same style as other filter indicators)
- Keybinding hint `t` shown in the controls bar at the bottom

---

## Files changed

| File | Change |
|---|---|
| `cli/src/domain.rs` | Add `ApplyType::EmailAvailable` variant |
| `cli/src/tui/job_list_screen.rs` | Add `ApplyTypeFilter::EmailAvailable` to enum, wire into `apply_filters()` cycle, display in stats |
| `cli/src/tui/app.rs` | No new keybinding — `t` cycles the existing `ApplyTypeFilter` |

---

## Out of scope

- Sending `?hasEmail=true` to the API (client-side filter only — no backend round-trip needed)
- Adding a dedicated filter UI widget (reuses existing cycle pattern)
