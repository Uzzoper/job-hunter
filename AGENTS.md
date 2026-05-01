# AGENTS.md — Job Hunter

> This file is read by OpenCode at every session.
> Never remove or edit without deliberate intent.

---

## About the project

**Job Hunter** is a Spring Boot application that automates the search for junior developer
job listings, analyzes each listing with AI, and generates personalized application emails.

### Main flow
```
Scraper (HTTP + Jsoup)
      ↓
Persistence (PostgreSQL + Flyway)
      ↓
AI Client (OpenRouter → MiniMax M2.5)
      ↓
Job analysis + personalized email generation
      ↓
REST API → web interface
```

### Target sites (priority order)
1. **Gupy** — accessible JSON endpoint, no authentication required
2. **Indeed BR** — scraping via Jsoup
3. **InfoJobs** — scraping via Jsoup
4. **LinkedIn** — basic scraping, no login (fallback)

---

## Architecture (Clean Architecture)

```
com.juanperuzzo.job_hunter
├── domain/
│   ├── model/       → Job, EmailDraft, JobAnalysis, CompanyTone, EmailStatus
│   └── exception/   → JobNotFoundException, ScraperException, AiException
│
├── application/
│   ├── port/
│   │   ├── in/      → FetchJobsUseCase, AnalyzeJobUseCase, GenerateEmailUseCase
│   │   └── out/     → JobRepository, EmailDraftRepository, ScraperPort, AiPort
│   └── service/     → FetchJobsService, AiAnalysisService, EmailGenerationService
│
├── infrastructure/
│   ├── scraper/     → GupyScraper (implements ScraperPort)
│   ├── ai/          → OpenRouterClient (implements AiPort)
│   ├── persistence/ → JobJpaRepository, JobPersistenceAdapter,
│   │                  EmailDraftJpaRepository, EmailDraftPersistenceAdapter
│   └── scheduler/   → JobHunterScheduler (@Scheduled)
│
└── web/
    ├── controller/  → JobController, EmailController
    ├── dto/         → JobResponse, EmailDraftResponse (records)
    └── exception/   → GlobalExceptionHandler (@RestControllerAdvice)
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
| Framework | Spring Boot 3.x |
| Build | Maven |
| Database | PostgreSQL via Docker Compose (dev and prod) |
| Migrations | Flyway |
| Scraping | Jsoup |
| HTTP client | Spring `RestClient` — never use `RestTemplate` |
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
All prompts are documented and versioned in `docs/prompts.md`.
Always read that file before implementing `AiAnalysisService` or `EmailGenerationService`.

---

## SDD — Specification-Driven Development

Every feature starts with a spec written **before** any code.

### Specs location
```
docs/specs/
├── _template.md
├── deduplicate-jobs.md
├── fetch-jobs.md
├── gupy-scraper.md
├── analyze-job.md
└── generate-email.md
```

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
```
src/test/java/com/juanperuzzo/job_hunter/
├── unit/
│   ├── application/
│   │   ├── FetchJobsServiceTest.java
│   │   ├── AiAnalysisServiceTest.java
│   │   └── EmailGenerationServiceTest.java
│   └── infrastructure/
│       ├── GupyScraperTest.java
│       └── OpenRouterClientTest.java
└── integration/
    ├── JobControllerIT.java
    └── GupyScraperIT.java
```

### Test rules by layer

| Layer | Test type | Tools |
|---|---|---|
| `domain` | Unit | Plain JUnit 5 — no mocks, no Spring |
| `application` | Unit | Mockito — mock all output ports |
| `infrastructure` | Unit | WireMock — simulate HTTP server |
| `web` | Integration | `@SpringBootTest` + `MockMvc` |

### Agent rules for TDD
- **Always generate the test BEFORE the production code**
- Show the test and ask "do you want to adjust before implementing?"
- After implementing, confirm which tests are now GREEN
- Suggest refactors after GREEN without changing behavior
- If code is requested without a test: generate both in the same response

---

## Development roadmap

### Phase 1 — Core (MVP)
- [ ] `pom.xml` dependencies (Jsoup, Flyway, WireMock, PostgreSQL)
- [ ] `docker-compose.yml` + `application.yaml`
- [ ] **[TDD]** `JobTest` — domain model RED
- [ ] `Job` record with `isExpired()` and URL-based `equals` → GREEN
- [ ] Migration `V1__create_jobs_table.sql`
- [ ] `JobRepository` port + JPA adapter
- [ ] **[TDD]** `FetchJobsServiceTest` RED
- [ ] `FetchJobsService` → GREEN → REFACTOR
- [ ] **[TDD]** `GupyScraperTest` RED (WireMock)
- [ ] `GupyScraper` → GREEN → REFACTOR

### Phase 2 — AI
- [ ] `OpenRouterClient` with `RestClient`
- [ ] **[TDD]** `AiAnalysisServiceTest` RED
- [ ] `AiAnalysisService` → GREEN → REFACTOR
- [ ] **[TDD]** `EmailGenerationServiceTest` RED
- [ ] `EmailGenerationService` → GREEN → REFACTOR
- [ ] `EmailDraft` record + migration `V2`

### Phase 3 — API
- [ ] `GET /api/jobs` — list jobs (filters: keyword, minScore)
- [ ] `GET /api/jobs/{id}` — job detail
- [ ] `POST /api/jobs/{id}/analyze` — analyze with AI
- [ ] `GET /api/jobs/{id}/email` — return generated email
- [ ] `POST /api/jobs/{id}/email` — generate new email

### Phase 4 — Automation
- [ ] `@Scheduled` running scrapers every 6 hours
- [ ] Configurable keyword filters via `application.yaml`

### Phase 5 — Interface
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