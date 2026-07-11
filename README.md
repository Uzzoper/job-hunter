# Job Hunter

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.6-brightgreen?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

A Spring Boot application that automates the search for junior developer job listings, analyzes each one with AI, and generates a personalized application email — ready to send.

---

## How it works

```mermaid
flowchart TB
    subgraph Auth["Authentication"]
        A1[POST /api/auth/register] --> A2[AuthService]
        A3[POST /api/auth/login] --> A2
        A2 --> A4[JwtTokenService]
        A4 --> A5[JWT Token]
    end

    subgraph Scraper["Job Scraping"]
        S1[Gupy API] --> S2[GupyProvider]
        S3[InfoJobs] --> S4[InfoJobsProvider]
        S2 --> S5[ProviderRegistry]
        S4 --> S5
        S5 --> S6[JobNormalizer]
        S6 --> S7[(PostgreSQL)]
    end

    subgraph AI["AI Analysis"]
        I1[POST /api/jobs/{id}/analyze] --> I2[AiAnalysisService]
        I2 --> I3[OpenRouter API<br/>MiniMax M2.5]
        I3 --> I4[JobAnalysis<br/>score + skills + tone]
    end

    subgraph Email["Email Generation"]
        E1[POST /api/jobs/{id}/email] --> E2[EmailGenerationService]
        E2 --> E3[OpenRouter API]
        E3 --> E4[EmailDraft<br/>ready to send]
    end

    Auth -->|Authorization: Bearer| Scraper
    Scraper --> AI
    AI --> Email
```

0. Register or login via `/api/auth/register` and `/api/auth/login` to receive a JWT token.
   All subsequent requests must include `Authorization: Bearer <token>`.
1. The scraper fetches job listings from Gupy and InfoJobs, filtered by keywords.
2. Each listing is saved to PostgreSQL — duplicates are skipped by URL.
3. On demand, the AI analyzes the listing against your profile and returns a match score (0–100), matched/missing skills, and company tone.
4. The AI then generates a personalized application email in Brazilian Portuguese, tailored to the company tone and mentioning a relevant portfolio project.

---

## Full API Flow

```mermaid
sequenceDiagram
    actor User
    participant API
    participant Auth as AuthService
    participant Scraper as ProviderRegistry
    participant AI as AiAnalysisService
    participant Email as EmailGenerationService
    participant DB as PostgreSQL

    User ->> API: POST /api/auth/register
    API ->> Auth: create user
    Auth ->> DB: save user
    DB -->> API: user created
    API -->> User: 201 Created

    User ->> API: POST /api/auth/login
    API ->> Auth: authenticate
    Auth ->> DB: verify credentials
    DB -->> Auth: user found
    Auth -->> API: JWT token
    API -->> User: { token, userId, name, email }

    Note over User,API: All subsequent requests include Authorization: Bearer <token>

    User ->> API: POST /api/jobs/fetch
    API ->> Scraper: fetchAll()
    Scraper ->> Scraper: retry + rate limit
    Scraper ->> Scraper: normalize + dedup
    Scraper ->> DB: save jobs
    DB -->> API: jobs saved
    API -->> User: { jobs[], count }

    User ->> API: POST /api/jobs/{id}/analyze
    API ->> AI: analyze(jobId)
    AI ->> AI: build prompt
    AI ->> AI: call OpenRouter
    AI ->> DB: save analysis
    DB -->> AI: analysis saved
    AI -->> API: { score, matchedSkills, missingSkills, companyTone }
    API -->> User: JobAnalysis

    User ->> API: POST /api/jobs/{id}/email
    API ->> Email: generate(jobId)
    Email ->> Email: build prompt
    Email ->> Email: call OpenRouter
    Email ->> DB: save draft
    DB -->> Email: draft saved
    Email -->> API: { subject, body }
    API -->> User: EmailDraft
```

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Architecture | Clean Architecture |
| Database | PostgreSQL 16 (Docker) |
| Migrations | Flyway |
| Security | Spring Security + JWT (jjwt) |
| Scraping | RestClient + Jsoup |
| AI | OpenRouter API (MiniMax M2.5) |
| Tests | JUnit 5 + Mockito + WireMock |
| Build | Maven |

---

## Architecture

This project follows Clean Architecture with strict layer separation:

```mermaid
flowchart BT
    subgraph Domain["🟢 Domain"]
        D1["model/ — Job, EmailDraft, JobAnalysis,<br/>CompanyTone, User, UserProfile"]
        D2["exception/ — ScraperException, AiException,<br/>JobNotFoundException, etc."]
    end

    subgraph Application["🔵 Application"]
        A1["port/in/ — FetchJobsUseCase,<br/>AnalyzeJobUseCase, GenerateEmailUseCase,<br/>AuthUseCase"]
        A2["port/out/ — JobRepository, ScraperPort,<br/>AiPort, UserRepository, etc."]
        A3["service/ — FetchJobsService,<br/>AiAnalysisService, EmailGenerationService,<br/>AuthService, UserProfileService"]
    end

    subgraph Infrastructure["🟠 Infrastructure"]
        I1["scraper/ — ProviderBasedScraper,<br/>GupyProvider, InfoJobsProvider"]
        I2["ai/ — OpenRouterClient"]
        I3["persistence/ — JPA adapters,<br/>repositories, entities"]
        I4["security/ — JWT filter,<br/>JwtTokenService, SecurityConfig"]
        I5["config/ — AppConfig"]
    end

    subgraph Web["🩷 Web"]
        W1["controller/ — JobController,<br/>EmailController, AuthController,<br/>ProfileController"]
        W2["dto/ — Request/Response records"]
        W3["exception/ — GlobalExceptionHandler"]
    end

    Web --> Application
    Infrastructure --> Application
    Application --> Domain
```

The dependency rule is strictly enforced: `domain` has no external dependencies, `application` depends only on `domain`, and `infrastructure`/`web` depend on `application`.

---

## Scraping Pipeline

```mermaid
flowchart LR
    Src["🌐 Source<br/>Gupy API / InfoJobs"]
    Prov["📡 Provider<br/>GupyProvider · InfoJobsProvider"]
    Strat["⚙️ Strategy<br/>ExtractionStrategy<br/>RestApiStrategy · HtmlStrategy"]
    Pars["🔍 Parser<br/>JsonLdParser"]
    Norm["🧹 Normalizer<br/>JobNormalizer · DateParser"]
    Adpt["🔌 Adapter<br/>ProviderBasedScraperAdapter"]

    Src --> Prov --> Strat --> Pars --> Norm --> Adpt
```

Each source is wrapped by a **Provider** that selects the right **Strategy** (REST API vs HTML). The parsed result is **Normalized** (dates, URLs, null-safe fields) and **Deduplicated** by URL before reaching the database via the **Adapter**.

---

## Getting started

### Prerequisites

- Java 21
- Docker + Docker Compose
- An [OpenRouter](https://openrouter.ai) API key (free tier works)

### Setup

**1. Clone the repository**

```bash
git clone https://github.com/Uzzoper/job-hunter.git
cd job-hunter
```

**2. Start the database**

```bash
docker compose up -d
```

**3. Create the local configuration file**

Create `src/main/resources/application-local.yaml`:

```yaml
spring:
  datasource:
    username: peruzzo
    password: jobhunter123

ai:
  openrouter:
    api-key: YOUR_OPENROUTER_API_KEY

jwt:
  secret: a-key-with-at-least-32-characters-for-hmac
```

> This file is in `.gitignore` and will never be committed.

**4. Run the application**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The application will start on `http://localhost:8080`. Flyway runs automatically and creates the database schema on first startup.

---

## API

| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|:---:|
| `POST` | `/api/auth/register` | Register a new user | No |
| `POST` | `/api/auth/login` | Login and receive JWT token | No |
| `GET` | `/api/jobs` | List all jobs | Yes |
| `GET` | `/api/jobs/{id}` | Get job detail | Yes |
| `POST` | `/api/jobs/fetch` | Trigger scraper manually | Yes |
| `POST` | `/api/jobs/{id}/analyze` | Analyze job with AI | Yes |
| `GET` | `/api/jobs/{id}/email` | Get generated email draft | Yes |
| `POST` | `/api/jobs/{id}/email` | Generate new email for the job | Yes |
| `GET` | `/api/profile` | Get authenticated user's profile | Yes |
| `PUT` | `/api/profile` | Save/update user profile | Yes |

---

## Running the tests

```bash
./mvnw test
```

The test suite uses WireMock to simulate HTTP servers for the scraper and AI client — no real API calls are made during testing.

---

## Development methodology

This project was built following **SDD (Specification-Driven Development)** and **TDD (Test-Driven Development)**:

- Every feature starts with a spec in `docs/specs/` (Given/When/Then)
- Tests are written before the implementation (RED → GREEN → REFACTOR)
- The AI coding assistant is guided by `AGENTS.md`, which documents the architecture, conventions, and workflow

---

## Project structure (docs)

```
docs/
└── specs/
    ├── _template.md
    ├── analyze-job.md
    ├── angular-frontend-spec.md
    ├── architecture.md       ← package structure, schema, architectural decisions
    ├── deduplicate-jobs.md
    ├── fetch-jobs.md
    ├── generate-email.md
    ├── gupy-scraper.md
    ├── indeed-scraper.md
    ├── infojobs-scraper.md
    ├── prompts.md            ← all AI prompts versioned and documented
    ├── user-authentication.md
    ├── user-profile.md
    ├── user-scoped-analysis.md
    └── use-case-refactoring.md
```

---

## License

This project is licensed under the **GNU General Public License v3.0**. See the [LICENSE](LICENSE) file for details.

## Author

**Juan Peruzzo**
[juanperuzzo.is-a.dev](https://juanperuzzo.is-a.dev) · [GitHub](https://github.com/Uzzoper)
