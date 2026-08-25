# Architecture — Job Hunter

> Technical reference for the project. Consulted by the OpenCode agent when making design decisions.
> Update whenever an architectural decision is revised.

---

## Overview

**Job Hunter** is a Spring Boot application that automates the search for junior developer job listings,
analyzes each listing with AI, and generates personalized application emails.

```
[Gupy API]            ──►  [GupyProvider]             ──┐
                                                        ├──► [ProviderRegistry] ──► [ProviderBasedScraperAdapter]
[InfoJobs HTML]       ──►  [InfoJobsProvider]           ──┘           │
                                                                       │
[LinkedIn (Playwright)] ──► [LinkedInScraperClient]     ──┘
       │                         (RestClient)
       ▼
[Node.js Service]
 (:3000, separate
  Docker container)
                                                                       │
                                                                       ▼
                                                               [FetchJobsService]  ◄──  [REST API] (manual)
                                                                       │
                                                                       ▼
                                                                [JobRepository]  ──►  [SQLite]
                    │
                    ▼ (on demand)
            [AiAnalysisService]  ──►  [OpenRouterClient]  ──►  [OpenRouter API]
                    │
                    ▼
         [EmailGenerationService]  ──►  [OpenRouterClient]
                    │
                    ▼
            [EmailDraftRepository]  ──►  [SQLite]
                    │
                    ▼
            [REST API]  ──►  [Web Interface]
```

---

## Architectural pattern: Clean Architecture

Layered separation ensures business logic does not depend on frameworks, databases, or external APIs.

### Dependency rule
```
web → application → domain
infrastructure → application
infrastructure → domain
```
No arrow points upward. `domain` does not import anything from Spring.

---

## Package structure

```
com.juanperuzzo.job_hunter
│
├── domain/                              ← core — no external dependencies
│   ├── model/
│   │   ├── Job.java                     (record)
│   │   ├── EmailDraft.java              (record)
│   │   ├── JobAnalysis.java             (record)
│   │   ├── CompanyTone.java             (enum)
│   │   └── EmailStatus.java             (enum)
│   └── exception/
│       ├── JobNotFoundException.java
│       ├── ScraperException.java
│       └── AiException.java
│
├── application/                         ← use cases and ports
│   ├── port/
│   │   ├── in/                          ← interfaces called by web layer
│   │   │   ├── AnalyzeJobUseCase.java
│   │   │   ├── AuthUseCase.java
│   │   │   ├── CurrentUserProvider.java
│   │   │   ├── FetchJobsUseCase.java
│   │   │   ├── GenerateEmailUseCase.java
│   │   │   ├── GetEmailDraftUseCase.java
│   │   │   ├── GetJobUseCase.java
│   │   │   ├── ListJobsUseCase.java
│   │   │   └── UserProfileUseCase.java
│   │   └── out/                         ← interfaces implemented by infrastructure
│   │       ├── AiPort.java
│   │       ├── EmailDraftRepository.java
│   │       ├── JobAnalysisRepository.java
│   │       ├── JobRepository.java
│   │       ├── NormalizerPort.java
│   │       ├── PasswordHasher.java
│   │       ├── ScraperPort.java                         (legacy — see ADR)
│   │       ├── SourceFetchPort.java
│   │       ├── TokenProvider.java
│   │       ├── UserProfileRepository.java
│   │       └── UserRepository.java
│   └── service/
│       ├── AiAnalysisService.java
│       ├── AuthService.java
│       ├── EmailGenerationService.java
│       ├── FetchJobsService.java
│       ├── FetchSourceJobsService.java              (uses SourceFetchPort + NormalizerPort)
│       └── UserProfileService.java
│
├── infrastructure/                      ← technical details
│   ├── scraper/
│   │   ├── ProviderBasedScraperAdapter.java  (implements ScraperPort)
│   │   ├── client/
│   │   │   └── LinkedInScraperClient.java    (ExtractionStrategy impl, calls Node.js Playwright service)
│   │   ├── provider/
│   │   │   ├── GupyProvider.java             (API strategy)
│   │   │   ├── InfoJobsProvider.java         (HTML strategy)
│   │   │   ├── LinkedInProvider.java         (Jsoup fallback)
│   │   │   └── ProviderRegistry.java         (implements SourceFetchPort)
│   │   ├── strategy/
│   │   │   ├── ExtractionStrategy.java       (interface)
│   │   │   ├── ApiStrategy.java              (JSON/API scraper)
│   │   │   └── HtmlStrategy.java             (Jsoup scraper)
│   │   ├── normalizer/
│   │   │   ├── DateParser.java
│   │   │   ├── JobNormalizer.java            (implements NormalizerPort)
│   │   │   └── RawJob.java                   (DTO)
│   │   ├── retry/
│   │   │   └── ExponentialBackoffRetry.java
│   │   └── ratelimit/
│   │       ├── RateLimiter.java              (interface)
│   │       └── TokenBucketRateLimiter.java
│   ├── ai/
│   │   └── OpenRouterClient.java        (implements AiPort)
│   ├── persistence/
│   │   ├── JobJpaRepository.java        (Spring Data)
│   │   ├── JobPersistenceAdapter.java   (implements JobRepository)
│   │   ├── EmailDraftJpaRepository.java
│   │   └── EmailDraftPersistenceAdapter.java
│   └── scheduler/                      ← (not implemented — manual trigger only)
│
└── web/                                 ← HTTP entry point
    ├── controller/
    │   ├── JobController.java
    │   └── EmailController.java
    ├── dto/
    │   ├── JobResponse.java             (record)
    │   └── EmailDraftResponse.java      (record)
    └── exception/
        └── GlobalExceptionHandler.java  (@RestControllerAdvice)
```

---

## Database

### Flyway migrations

```
src/main/resources/db/migration/
├── V1__create_jobs_table.sql
├── V2__create_email_drafts_table.sql
└── V3__create_users_and_profiles_tables.sql
```

### Simplified schema

```sql
-- V1
CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    company     VARCHAR(255) NOT NULL,
    url         VARCHAR(500) NOT NULL UNIQUE,
    description TEXT,
    posted_at   DATE NOT NULL,
    match_score INTEGER,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- V2
CREATE TABLE email_drafts (
    id           BIGSERIAL PRIMARY KEY,
    job_id       BIGINT NOT NULL REFERENCES jobs(id),
    subject      VARCHAR(255) NOT NULL,
    body         TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_at TIMESTAMP DEFAULT NOW()
);
```

---

## Architectural decisions

### Why Clean Architecture?
Allows swapping the scraper (Gupy → LinkedIn), the database (SQLite → PostgreSQL),
or the AI provider (OpenRouter → Groq) without touching the services.
Each change is isolated to the `infrastructure` layer.

### Why RestClient instead of WebClient?
The project does not need reactivity — HTTP calls are synchronous and infrequent.
`RestClient` is simpler and does not require Reactor on the classpath.

### Why WireMock for scraper tests?
Simulates the HTTP server locally — fast tests, no network dependency,
no API key required in CI.

### Why SQLite as the only database?
The app is local-first: each user clones and runs it on their own machine, so the
database must require zero infrastructure. SQLite is a single file (`./data/jobhunter.db`)
— no server, no Docker, no credentials — and backups are file copies. Flyway owns the
schema with a consolidated baseline migration. WAL mode allows concurrent reads during
scraper/scheduler writes. The repository ports keep a future PostgreSQL re-adoption
(e.g. a hosted SaaS) cheap — only the `infrastructure` layer would change.

### Why records for DTOs and domain models?
Records are immutable by default, have auto-generated `equals`/`hashCode`/`toString`,
and communicate immutability intent clearly.

### Why Playwright over Jsoup for LinkedIn?
LinkedIn serves different content to headless vs headed browsers. Playwright renders JavaScript-rendered content, which more closely mirrors what a real browser would display. Jsoup only parses static HTML and may receive a Bot Challenge page instead of actual job data. The Jsoup-based `LinkedInProvider` is kept as a lightweight fallback for environments without Docker.

### Why a separate microservice for LinkedIn scraping?
Playwright requires heavy Chromium dependencies (600MB+). A separate Node.js + Express + TypeScript microservice keeps the Spring Boot container lightweight (no Playwright/Chromium in the JVM image) and allows independent scaling of the scraping layer.

### How do the Java and Node.js services communicate?
The Spring Boot app accesses the Node.js scraper via `http://linkedin-scraper:3000` in Docker (Compose service name resolves via internal DNS). Local development overrides to `http://localhost:3000` via `application-local.yaml`.

### Why both random delays and a token bucket rate limiter?
They serve different layers. The `TokenBucketRateLimiter` controls HTTP request rate to external APIs (Gupy, InfoJobs) to avoid 429 responses. The random delays in the Playwright scraper (`linkedin-scraper`) simulate human navigation patterns between page interactions to avoid bot detection. One is network-level rate limiting, the other is browser-level behavior simulation — they are complementary, not redundant.

---

## REST endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/jobs` | List jobs (filters: `keyword`, `minScore`) |
| `GET` | `/api/jobs/{id}` | Job detail |
| `POST` | `/api/jobs/fetch` | Trigger all scrapers (Gupy + InfoJobs + LinkedIn) |
| `POST` | `/api/jobs/fetch/linkedin` | Trigger only LinkedIn scraper (requires `service` mode) |
| `POST` | `/api/jobs/{id}/analyze` | Analyze job with AI |
| `GET` | `/api/jobs/{id}/email` | Return generated email |
| `POST` | `/api/jobs/{id}/email` | Generate new email for the job |

---

## CLI / TUI Client (Rust)

Separate crate at `cli/`. Two modes:

| Mode | Entry | Spec |
|------|-------|------|
| **TUI** (interactive) | `jh-cli` (no args) | `docs/specs/cli-tui-spec.md` |
| **Batch** (scriptable) | `jh-cli <cmd>` | `docs/specs/cli-tui-spec.md` |

Architecture:
```
Spring Boot API (localhost:8080)
       ▲
       │ HTTP/JSON + JWT
       ▼
┌──────────────────┐
│  jh-cli (Rust)   │
├──────────────────┤
│  ApiClient       │  ← Reqwest + Tokio
│  CacheManager    │  ← Rusqlite (SQLite, local)
│  ConfigManager   │  ← TOML + env vars
│  TUI (Ratatui)   │  ← Crossterm
│  Batch (Clap)    │  ← Subcommands
└──────────────────┘
```

See `docs/specs/cli-tui-spec.md` for full command reference, keybindings, data models, caching strategy, and test structure.

---

## Configuration (`application.yaml`)

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:sqlite:./data/jobhunter.db}
    driver-class-name: org.sqlite.JDBC
  flyway:
    enabled: true

ai:
  openrouter:
    base-url: https://openrouter.ai/api/v1
    api-key: ${OPENROUTER_API_KEY}
    model: minimax/minimax-m2.5
    timeout-seconds: 30

scraper:
  retry:
    max-attempts: 3
    base-delay-millis: 1000
    max-delay-millis: 30000
    max-jitter-millis: 2000
  rate-limiter:
    default-permits-per-second: 5
    default-burst: 3
  normalizer:
    max-age-days: 90
  gupy:
    keywords: desenvolvedor,developer,engenheiro de software
    limit: 100
    timeout-seconds: 10
  infojobs:
    keywords: desenvolvedor junior,analista desenvolvedor junior
    max-pages: 3
    timeout-seconds: 10
  linkedin:
    enabled: true
    mode: service          # "service" (Playwright) | "jsoup" (fallback)
    service-url: http://linkedin-scraper:3000
    keywords: "desenvolvedor junior,...,junior developer"
    locations: "Brazil,São Paulo,Remote"
    max-jobs: 25
    timeout-seconds: 30

  # No automatic scheduler — manual trigger via POST /api/jobs/fetch
```
