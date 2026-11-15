# Session 2026-09-17 — Batch validation & eligibility screening

## Pre-flight validation gates (applied this session)

Before emitting an intent or driving the browser, run these gates IN ORDER:

1. **Backend health check** — `GET http://localhost:8080/api/jobs` must return HTTP 200. A 401 or unreachable host means the backend auth or service is down — stop and report.
2. **Idempotency** — `GET /api/jobs/{id}` must NOT return `lifecycleState: "SUBMITTED"` or `status: "applied"`. If already submitted, stop.
3. **URL integrity** — URLs in the Job Hunter DB are ALWAYS intact. The `...` seen in terminal/`read_file` is DISPLAY truncation, NOT a real truncation. NEVER hex-decode. If `length(url)` is ~110-120, the URL is intact — navigate directly.
4. **Job availability** — navigate to the public Gupy page. 404 / "page not found" / missing from public listing → mark `NOT_AVAILABLE`, move on. DO NOT submit.
5. **Eligibility screening (pre-apply)** — read the job description. If ANY hard requirement (not "desejável") is unmet by the profile, mark `ELIGIBILITY_BLOCK` and DO NOT apply:
   - "Formado em..." / "Ensino superior completo" → BLOCK (check declared education status in bot memory)
   - "X+ anos de experiência" where X > 0 → BLOCK (junior, 0 years formal)
   - Stack explicitly lacking (e.g., PHP + Angular + CodeIgniter) → BLOCK (check declared stack answers in bot memory first — they evolve)
   - Methods user cannot honestly claim → BLOCK

## Screening answer defaults (from bot memory)

Real values live in **bot memory** (`memories/MEMORY.md`) — never in this
public repository. Format:

| Question | Default | Notes |
|---|---|---|
| *(tech/skill question)* | `<Sim/Não + detail>` | `<memory note>` |
| *(eligibility question)* | `<declared value>` | `<memory note>` |

## Status taxonomy for batch runs

- `applied` — submitted + success evidence
- `rejected` — submitted + eliminatory failure from portal (e.g., a knockout requirement the user lacks)
- `NOT_AVAILABLE` — job vanished from public listing (404)
- `ELIGIBILITY_BLOCK` — pre-apply hard requirement unmet (never submitted)
- `already_applied` — backend says SUBMITTED

## Batch results (≥70) — format example; live values stay in the local DB

| Job | Score | Status | Reason |
|---|---|---|---|
| `<id>` | 82 | applied | `<time>` |
| `<id>` | 78 | rejected | `<eliminatory stack>` eliminatory |
| `<id>` | 80 | rejected | `<knockout requirement>` eliminatory |
| `<id>` | 78 | NOT_AVAILABLE | `<company>` 404 |
| `<id>` | 78 | NOT_AVAILABLE | `<company>` redirected to listing |
| `<id>` | 75 | ELIGIBILITY_BLOCK | "Formado em..." + "5+ anos" |
| `<id>` | 75 | IN_PROGRESS | session expired before submit |
