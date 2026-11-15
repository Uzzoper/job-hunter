# LinkedIn Read-Only Extraction Workflow

## Context

LinkedIn job listing extraction for the `jobhunter-bot` profile. This is a **read-only** operation: extract listing data (title, company, URL, location, model) and save to JSONL. Never apply, click, navigate to detail pages, or interact with listings.

## What works

### 1. Open URL in preview pane
```
tool_call: desktop_preview { action: "open", url: "<linkedin-search-url>" }
```
This opens the URL in the Hermes desktop preview pane (visible to the user).

### 2. Read visible text
```
tool_call: desktop_preview { action: "read" }
```
Returns `{kind, url, title, text, start, end, total_chars}`. The `text` field contains the rendered page text including job titles, companies, locations, and remote/hybrid/on-site markers.

### 3. Extract from AX snapshot
```
tool_call: drive_preview { action: "elements" }
```
Returns structured element inventory with labels for each job card. Useful for identifying which elements correspond to which listings.

## What does NOT work

### `drive_preview evaluate` — NOT a valid action
`drive_preview` only supports: `elements`, `click`, `hover`, `type`, `scroll`, `press`, `strobe`, `back`, `forward`, `reload`. There is **no** `evaluate` or JS execution action. Calling `evaluate` returns a validation error.

### AX snapshot does NOT include hrefs
The accessibility tree provides title/company/location text but **NOT** the underlying job URLs (`href` attributes). URLs require DOM access.

### Playwright MCP may not be initialized
If the Playwright MCP server returns `"Bad Request: Server not initialized"` or similar, the session is not active. Do not improvise.

## Correct approach for URLs

1. Try Playwright MCP `browser_evaluate` (if session is active) to extract hrefs from DOM without clicking anything.
2. If Playwright MCP is unavailable or not initialized, **stop and report** — do not attempt CDP direct, Python scripts, or other workarounds.
3. User directive: "Se o MCP falhar ou pedir login de novo, pare e reporte em vez de improvisar."

## Output format

Save extracted jobs as JSONL (one object per line) with fields:
```json
{"title": "...", "company": "...", "url": "...", "location": "...", "model": "remote|hybrid|on-site"}
```

If URLs cannot be extracted, leave the field empty and report partial completion. Do not block on missing URLs — deliver what was extracted and note what is missing.

## Time budget

User directive: "conclua em 10 minutos o que der e reporte quantas saíram completas, sem travar em ferramenta." If extraction is taking too long, deliver partial results and report counts.

## Permanent rules (from user directive 2026-09-24)

1. Max 3 read runs per day, spaced at least 2 hours apart, max 30 jobs per run.
2. On any checkpoint/CAPTCHA/identity verification/security screen: stop immediately, request human takeover. Never solve CAPTCHA alone, never create account, never post/like/comment/message.
3. Read-only: extract and save only. Never apply or interact with listings without explicit per-job user instruction.
4. If account suffers any restriction: notify immediately and pause all LinkedIn work until explicit user clearance.
5. Strictly on-demand: only when user explicitly asks in chat. Never by own initiative. Never create/suggest cron jobs or scheduled routines involving LinkedIn.