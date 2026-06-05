# AGENTS.md — Job Hunter

> This file is read by OpenCode at every session.
> Never remove or edit without deliberate intent.

---

## About the project

**Job Hunter** is a Spring Boot application that automates the search for junior developer
job listings, analyzes each listing with AI, and generates personalized application emails.

### Main flow
```
User Register/Login → JWT token
       ↓
All requests require Authorization: Bearer
       ↓
JwtTokenFilter → CurrentUserService
       ↓
Scraper (CompositeScraper: Gupy + InfoJobs)
       ↓
Persistence (PostgreSQL + Flyway)
       ↓
AI Client (OpenRouter → MiniMax M2.5)
       ↓
Job analysis + personalized email generation
       ↓
REST API (auth required except register/login)
```

### Target sites (priority order)
1. **Gupy** — accessible JSON endpoint
2. **InfoJobs** — scraping via Jsoup
3. Both orchestrated by CompositeScraper

---

## Architecture (Clean Architecture)

```
com.juanperuzzo.job_hunter
├── domain/                      ← pure Java, no framework dependencies
│   ├── model/                   → Entities (Job, User, EmailDraft, etc.)
│   └── exception/               → Custom exceptions
│
├── application/                 ← use cases and ports
│   ├── port/in/                 → Use case interfaces
│   ├── port/out/                → Repository interfaces (outbound ports)
│   └── service/                 → Use case implementations
│
├── infrastructure/              ← technical details
│   ├── scraper/                 → GupyScraper, InfoJobsScraper, CompositeScraper
│   ├── ai/                      → OpenRouterClient
│   ├── persistence/             → JPA adapters per entity
│   ├── security/                → JWT filter, token service, CurrentUserService
│   ├── scheduler/               → JobHunterScheduler
│   └── config/                  → AppConfig
│
└── web/                         ← REST controllers
    ├── controller/              → Endpoints
    ├── dto/                     → Request/Response records
    └── exception/               → GlobalExceptionHandler
```

### Dependency rule
```
web → application → domain
infrastructure → application
infrastructure → domain
```
`domain` must never import anything from Spring, JPA, or any framework.

---

## Technical stack

| Layer | Technology |
|---|---|
| Language | Java 21 — use records, text blocks, var |
| Framework | Spring Boot 4.0.6 |
| Build | Maven |
| Database | PostgreSQL via Docker Compose (dev and prod) |
| Migrations | Flyway |
| Security | Spring Security + JWT (jjwt) |
| Scraping | Jsoup + RestClient |
| AI | OpenRouter API (OpenAI-compatible) |
| Logging | SLF4J (`private static final Logger log`) |
| Tests | JUnit 5 + Mockito + WireMock |

---

## Code rules

### General
- Always use **records** for DTOs and immutable objects
- Never hardcode secrets — all keys go in `application.yaml` via `@Value`
- Never use `RestTemplate` — only `RestClient`
- Use `Optional` correctly — never call `.get()` without checking
- Prefer `var` when the type is obvious on the same line
- No Lombok — use Java 21 records, constructors, and static factory methods
- Document public methods with Javadoc when logic is non-trivial

### Naming
- Classes: `PascalCase`
- Methods and variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Database tables: `snake_case` (e.g. `jobs`, `email_drafts`)

### Primary keys
- Use `Long` in domain models and `BIGSERIAL` in SQL
- `id` is `null` until persisted — never use it for equality checks
- `Job` identity is always URL-based

### Flyway migrations
- Format: `V{number}__{description_in_english}.sql`
- Example: `V1__create_jobs_table.sql`, `V2__create_email_drafts_table.sql`
- Never modify a committed migration — always create a new one

### Error handling
- Always use a centralized `@RestControllerAdvice`
- Throw custom domain exceptions (in `domain/exception/`)
- Never expose stack traces in HTTP responses

### Tests
- Name format: `methodName_scenario_expectedResult()`
  - ✅ `fetchAndSave_whenDuplicateUrl_shouldSkipJob()`
  - ✅ `analyze_whenEmptyDescription_shouldThrowIllegalArgument()`
  - ❌ `testFetch()`
- Use `@DisplayName` with a descriptive sentence
- Unit tests must never start a Spring context — plain JUnit 5 + Mockito only

---

## AI integration (OpenRouter)

### Configuration
```yaml
ai:
  openrouter:
    base-url: https://openrouter.ai/api/v1
    api-key: ${OPENROUTER_API_KEY}
    model: minimax/minimax-m2.5
    timeout-seconds: 30
```

### Prompts
All prompts are documented and versioned in `docs/specs/prompts.md`.
Always read that file before implementing `AiAnalysisService` or `EmailGenerationService`.

---

## SDD — Specification-Driven Development

Every feature starts with a spec written **before** any code.

### Specs location

All specs live in `docs/specs/`. Check the directory before implementing a new feature.

### Agent rules for SDD
- **Never generate code for a feature without an existing spec**
- If code is requested without a spec: ask whether to create the spec first
- When generating code, reference which spec is being implemented
- If code diverges from the spec: point out the divergence and ask for confirmation

---

## TDD — Test-Driven Development

All business logic follows the **RED → GREEN → REFACTOR** cycle.

### Mandatory cycle per feature
```
1. Write the test (fails — RED)
2. Write the minimum code to pass (GREEN)
3. Refactor while keeping tests green (REFACTOR)
```

### Test structure

Tests follow the same package structure under `src/test/java/.../`. Check the directory for the full list of test files.

### Test rules by layer

| Layer | Test type | Tools |
|---|---|---|
| `domain` | Unit | Plain JUnit 5 — no mocks, no Spring |
| `application` | Unit | Mockito — mock all output ports |
| `infrastructure` | Unit | WireMock — simulate HTTP server |
| `web` | Unit | `@WebMvcTest` + `MockMvc` |

### Agent rules for TDD
- **Always generate the test BEFORE the production code**
- Show the test and ask "do you want to adjust before implementing?"
- After implementing, confirm which tests are now GREEN
- Suggest refactors after GREEN without changing behavior
- If code is requested without a test: generate both in the same response

---

## Development roadmap

### Phase 1 — Core (MVP) — ✅ Complete
- [x] `pom.xml` dependencies (Jsoup, Flyway, WireMock, PostgreSQL)
- [x] `docker-compose.yml` + `application.yaml`
- [x] **[TDD]** `JobTest` — domain model RED
- [x] `Job` record with `isExpired()` and URL-based `equals` → GREEN
- [x] Migration `V1__create_jobs_table.sql`
- [x] `JobRepository` port + JPA adapter
- [x] **[TDD]** `FetchJobsServiceTest` RED
- [x] `FetchJobsService` → GREEN → REFACTOR
- [x] **[TDD]** `GupyScraperTest` RED (WireMock)
- [x] `GupyScraper` → GREEN → REFACTOR

### Phase 2 — AI — ✅ Complete
- [x] `OpenRouterClient` with `RestClient`
- [x] **[TDD]** `AiAnalysisServiceTest` RED
- [x] `AiAnalysisService` → GREEN → REFACTOR
- [x] **[TDD]** `EmailGenerationServiceTest` RED
- [x] `EmailGenerationService` → GREEN → REFACTOR
- [x] `EmailDraft` record + migration `V2`

### Phase 3 — API — ✅ Complete
- [x] `GET /api/jobs` — list jobs (filters: keyword, minScore)
- [x] `GET /api/jobs/{id}` — job detail
- [x] `POST /api/jobs/{id}/analyze` — analyze with AI
- [x] `GET /api/jobs/{id}/email` — return generated email
- [x] `POST /api/jobs/{id}/email` — generate new email

### Phase 4 — Automation — ⏳ Pending
- [ ] `@Scheduled` running scrapers every 6 hours
- [ ] Configurable keyword filters via `application.yaml`

### Phase 5 — Interface — ⏳ Pending
- [ ] Simple HTML page or Angular SPA
- [ ] Dashboard with job list and "Generate email" button

---

## Commit convention

All commits must follow the **Conventional Commits** standard.

### Format
```
<type>(optional scope): <short description in English>
```

### Types
| Type | When to use |
|---|---|
| `feat` | New feature or behavior |
| `fix` | Bug fix |
| `docs` | Documentation only (md files, comments) |
| `test` | Adding or updating tests |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `chore` | Build, config, dependencies, tooling |
| `style` | Formatting only (no logic change) |

### Scope (optional)
Use the Clean Architecture layer name: `domain`, `application`, `infrastructure`, `web`, `scheduler`, `config`

### Examples
```
feat(application): add FetchJobsService with deduplication logic
feat(application): add AiAnalysisService with matchScore clamping
feat(application): add EmailGenerationService
feat(infrastructure): implement GupyScraper with keyword filtering
feat(infrastructure): implement OpenRouterClient for AI completion
feat(persistence): add JobRepository port and JPA adapter
fix(infrastructure): handle null description on job mapping
test(domain): add JobTest covering isExpired and URL equality
refactor(application): extract prompt building into private method
docs: add gupy-scraper spec
chore(config): add Jsoup and WireMock dependencies to pom.xml
```

### Rules
- Description in English, lowercase, no period at the end
- Maximum 72 characters in the subject line
- Use imperative mood: "add", "fix", "implement" — not "added", "fixed"
- One logical change per commit — never mix feature + refactor in the same commit

---

## How the agent should behave

- **Always generate complete code** — never use `// ... rest of the code`
- **Explain architectural decisions** when they are not obvious
- **Point out code smells** before generating code that contains them
- **Suggest the next step** when finishing each task
- **Explain in Portuguese**, write code and comments in English
- When choosing between two approaches, briefly present both and recommend one
- Always verify the solution does not violate any rule in this AGENTS.md before responding