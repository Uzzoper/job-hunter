# Session 2026-09-17 — Batch validation & eligibility screening

## Pre-flight validation gates (applied this session)

Before emitting an intent or driving the browser, run these gates IN ORDER:

1. **Backend health check** — `GET http://localhost:8080/api/jobs` must return HTTP 200. A 401 or unreachable host means the backend auth or service is down — stop and report.
2. **Idempotency** — `GET /api/jobs/{id}` must NOT return `lifecycleState: "SUBMITTED"` or `status: "applied"`. If already submitted, stop.
3. **URL integrity** — URLs in the Job Hunter DB are ALWAYS intact. The `...` seen in terminal/`read_file` is DISPLAY truncation, NOT a real truncation. NEVER hex-decode. If `length(url)` is ~110-120, the URL is intact — navigate directly.
4. **Job availability** — navigate to the public Gupy page. 404 / "page not found" / missing from public listing → mark `NOT_AVAILABLE`, move on. DO NOT submit.
5. **Eligibility screening (pre-apply)** — read the job description. If ANY hard requirement (not "desejável") is unmet by the profile, mark `ELIGIBILITY_BLOCK` and DO NOT apply:
   - "Formado em..." / "Ensino superior completo" → BLOCK (Juan cursing, 2027)
   - "X+ anos de experiência" where X > 0 → BLOCK (junior, 0 years formal)
   - Stack explicitly lacking (e.g., PHP + Angular + CodeIgniter) → BLOCK (but Angular is now "Sim")
   - Methods user cannot honestly claim → BLOCK

## Screening answer defaults (from bot memory)

| Question | Default | Notes |
|---|---|---|
| Angular | Sim | 2 projetos próprios, aprende rápido |
| Superior completo | Não | Cursando, 2027 |
| Java | Sim | Spring Boot |
| React | Sim | Hooks, Redux, Next.js |
| SQL | Sim | PostgreSQL, MySQL |
| Testes unitários | Sim | JUnit 5, Jest, Mockito |
| Git/GitLab/CI-CD | Sim | GitHub Actions |
| Docker | Sim | Docker Compose |
| Cloud | Básico/Sim | Conhecimento geral |
| Inglês | C1 intermediário | |
| Pretensão salarial | Aberto a negociação | Mercado júnior |

## Status taxonomy for batch runs

- `applied` — submitted + success evidence
- `rejected` — submitted + eliminatory failure from portal (e.g., superior incompleto)
- `NOT_AVAILABLE` — job vanished from public listing (404)
- `ELIGIBILITY_BLOCK` — pre-apply hard requirement unmet (never submitted)
- `already_applied` — backend says SUBMITTED

## Today's batch results (≥70)

| Job | Score | Status | Reason |
|---|---|---|---|
| 421 | 82 | applied | 12:40h |
| 21 | 78 | rejected | PHP/Angular/CodeIgniter eliminatory |
| 559 | 80 | rejected | Superior incompleto eliminatory |
| 68 | 78 | NOT_AVAILABLE | FCamara 404 |
| 122 | 78 | NOT_AVAILABLE | Cacau Show redirected to listing |
| 1481 | 78 | NOT_AVAILABLE | Boticário listing |
| 1726 | 78 | NOT_AVAILABLE | Boticário listing |
| 380 | 77 | NOT_AVAILABLE | UOL EdTech listing |
| 6 | 75 | NOT_AVAILABLE | Conexa 404 |
| 185 | 75 | NOT_AVAILABLE | Energisa 404 |
| 896 | 80 | NOT_AVAILABLE | The Coffee 404 |
| 900 | 80 | NOT_AVAILABLE | Poupacred 404 |
| 193 | 75 | ELIGIBILITY_BLOCK | "Formado em..." + "5+ anos" |
| 274 | 75 | IN_PROGRESS | SoftExpert Java/React — session expired before submit |
