# Spec: Navigation Auth/Loop Guard (issue #38)

> **Layer:** `skills/job-application` (pure Python, stdlib-only)
> **Implementation file:** `skills/job-application/navigation.py`
> **Corresponding test:** `skills/job-application/navigation_test.py`

---

## Expected behavior

### Scenario 1: Auth URL detected immediately
- **GIVEN** the bot is about to navigate to `https://jobs.gupy.io/candidates/auth`
- **WHEN** the bot checks `is_auth_url(url)`
- **THEN** it returns `True`

### Scenario 2: Auth URL detected — early abort
- **GIVEN** the bot visited a normal job URL once, then landed on `https://jobs.gupy.io/login?return=/jobs/123`
- **WHEN** the bot calls `NavigationGuard.record(url)` with the login URL
- **THEN** the result has `auth_detected=True` and `loop_detected=False`
- **AND** `manual_url` is the original job URL (passed at construction)

### Scenario 3: Same URL visited 3 times → loop detected
- **GIVEN** the bot visited `https://jobs.gupy.io/jobs/123` twice before
- **WHEN** the bot calls `NavigationGuard.record(url)` a third time with the same URL
- **THEN** the result has `loop_detected=True` and `auth_detected=False`
- **AND** `visit_count` is 3

### Scenario 4: Same URL visited 2 times → no loop yet
- **GIVEN** the bot visited `https://jobs.gupy.io/jobs/123` once before
- **WHEN** the bot calls `NavigationGuard.record(url)` a second time
- **THEN** the result has `loop_detected=False` and `visit_count` is 2

### Scenario 5: URL normalization strips query/fragment
- **GIVEN** URLs `https://jobs.gupy.io/jobs/123?ref=abc` and `https://jobs.gupy.io/jobs/123#top`
- **WHEN** both are normalized
- **THEN** they both normalize to `https://jobs.gupy.io/jobs/123`

### Scenario 6: NavigationGuard integrates into apply.py CLI
- **GIVEN** the bot calls `apply.py --job-url <url> --profile p.json --portal gupy --current-url <auth-url> --visited-urls <url>,<url>,<url>`
- **WHEN** the current URL is an auth URL or the visited-urls list hits 3+ for the same normalized URL
- **THEN** apply.py exits with code 1 and emits `{"error": "auth_required" | "navigation_loop", "detail": "...", "manual_url": "<job-url>", "screenshot_path": "..."}`

---

## Business rules

- **Rule 1** — Auth detection: URL path contains `/login`, `/auth`, `/signin`, or `/candidates/auth` (case-insensitive match on the path component after lowercasing).
- **Rule 2** — Loop detection: a URL is considered the same when normalized (fragment and query stripped, host lowercased). If the same normalized URL appears 3+ times in the visited-urls history, the loop guard fires.
- **Rule 3** — Both guards are independent; either fires produces a structured JSON error with `manual_url` pointing to the original job URL and `screenshot_path` following the existing `<memory>/screenshots/<job_id>.png` convention.
- **Rule 4** — `normalize_url()` strips query parameters and fragment, lowercases scheme+host, and removes trailing slashes.
- **Rule 5** — The guard is stateless (pure functions + a small class). The CLI integration in apply.py keeps the planner stateless by receiving visited history as arguments.

---

## Error cases

| Situation | Error code | Expected behavior |
|---|---|---|
| Current URL matches auth pattern | `auth_required` | JSON error with `manual_url`, `screenshot_path`; exit 1 |
| Same URL visited 3+ times | `navigation_loop` | JSON error with `manual_url`, `screenshot_path`; exit 1 |

---

## Out of scope

- Does not perform actual browser navigation — the bot's browser tool does that.
- Does not persist visited-URL history to disk — the bot passes it each invocation.
- Does not detect JavaScript-based redirects or meta-refresh.
- Does not handle CAPTCHA — already documented as out of scope in SKILL.md.
