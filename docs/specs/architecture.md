# Architecture — Job Hunter

> Technical reference for the project. Consulted by the OpenCode agent when making design decisions.
> Update whenever an architectural decision is revised.

---

## Overview

**Job Hunter** is a Spring Boot application that automates the search for junior developer job listings,
analyzes each listing with AI, and generates personalized application emails.

```
[Gupy API] ──► [GupyScraper]
                     │
                     ▼
             [FetchJobsService]  ◄──  @Scheduled (every 6h)
                     │
                     ▼
             [JobRepository]  ──►  [PostgreSQL]
                     │
                     ▼ (on demand)
             [AiAnalysisService]  ──►  [OpenRouterClient]  ──►  [OpenRouter API]
                     │
                     ▼
          [EmailGenerationService]  ──►  [OpenRouterClient]
                     │
                     ▼
             [EmailDraftRepository]  ──►  [PostgreSQL]
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
├── application/                         ← use cases
│   ├── port/
│   │   ├── in/                          ← interfaces called by web layer
│   │   │   ├── FetchJobsUseCase.java
│   │   │   ├── AnalyzeJobUseCase.java
│   │   │   └── GenerateEmailUseCase.java
│   │   └── out/                         ← interfaces implemented by infrastructure
│   │       ├── JobRepository.java
│   │       ├── EmailDraftRepository.java
│   │       ├── ScraperPort.java
│   │       └── AiPort.java
│   └── service/
│       ├── FetchJobsService.java
│       ├── AiAnalysisService.java
│       └── EmailGenerationService.java
│
├── infrastructure/                      ← technical details
│   ├── scraper/
│   │   └── GupyScraper.java             (implements ScraperPort)
│   ├── ai/
│   │   └── OpenRouterClient.java        (implements AiPort)
│   ├── persistence/
│   │   ├── JobJpaRepository.java        (Spring Data)
│   │   ├── JobPersistenceAdapter.java   (implements JobRepository)
│   │   ├── EmailDraftJpaRepository.java
│   │   └── EmailDraftPersistenceAdapter.java
│   └── scheduler/
│       └── JobHunterScheduler.java      (@Scheduled)
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
└── V3__add_match_score_to_jobs.sql      (after AI analysis is implemented)
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
Allows swapping the scraper (Gupy → LinkedIn), the database (PostgreSQL → MongoDB),
or the AI provider (OpenRouter → Groq) without touching the services.
Each change is isolated to the `infrastructure` layer.

### Why RestClient instead of WebClient?
The project does not need reactivity — HTTP calls are synchronous and infrequent.
`RestClient` is simpler and does not require Reactor on the classpath.

### Why WireMock for scraper tests?
Simulates the HTTP server locally — fast tests, no network dependency,
no API key required in CI.

### Why PostgreSQL in both dev and prod?
Identical environments eliminate "works locally, breaks in prod" surprises.
Flyway guarantees the schema is the same in both environments.
PostgreSQL runs via Docker Compose in development.

### Why records for DTOs and domain models?
Records are immutable by default, have auto-generated `equals`/`hashCode`/`toString`,
and communicate immutability intent clearly.

---

## REST endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/jobs` | List jobs (filters: `keyword`, `minScore`) |
| `GET` | `/api/jobs/{id}` | Job detail |
| `POST` | `/api/jobs/{id}/analyze` | Analyze job with AI |
| `GET` | `/api/jobs/{id}/email` | Return generated email |
| `POST` | `/api/jobs/{id}/email` | Generate new email for the job |

---

## Configuration (`application.yaml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jobhunter
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  flyway:
    enabled: true

ai:
  openrouter:
    base-url: https://openrouter.ai/api/v1
    api-key: ${OPENROUTER_API_KEY}
    model: minimax/minimax-m2.5
    timeout-seconds: 30

scraper:
  gupy:
    keywords: desenvolvedor,developer,estagiário,engenheiro de software
    limit: 20
    timeout-seconds: 5

scheduler:
  fetch-jobs:
    cron: "0 0 */6 * * *"
```
