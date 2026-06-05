# Job Hunter

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.6-brightgreen?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)
![License](https://img.shields.io/badge/license-MIT-green)

A Spring Boot application that automates the search for junior developer job listings, analyzes each one with AI, and generates a personalized application email — ready to send.

---

## How it works

```
User ──► POST /api/auth/register ──► AuthService ──► UserRepository ──► PostgreSQL
                                            │
                           POST /api/auth/login
                    AuthService ──► JwtTokenService ──► JWT Token
                                            │
          ┌─────────────────────────────────┘
          ▼
  All subsequent requests require Authorization: Bearer <token>
          │
          ▼
  JwtTokenFilter (validates JWT on every request)
          │
          ▼
  CurrentUserService.getCurrentUserId() ──► Controller ──► Service ──► Repository
          │
  ┌───────┴──────────────────────────────────────────────────────────────────┐
  │                                                                          │
  ▼                                                                          ▼
Gupy API ──► GupyScraper                                          POST /api/jobs/{id}/analyze
       ──► InfoJobsScraper  ──► FetchJobsService ──► PostgreSQL         AiAnalysisService
       ──► CompositeScraper                                                    │
                                                                               ▼
                                                                      OpenRouter (MiniMax M2.5)
                                                                               │
                                                                               ▼
                                                                   POST /api/jobs/{id}/email
                                                                   EmailGenerationService
                                                                               │
                                                                               ▼
                                                                   EmailDraft (ready to send)
```

0. Register or login via `/api/auth/register` and `/api/auth/login` to receive a JWT token.
   All subsequent requests must include `Authorization: Bearer <token>`.
1. The scraper fetches job listings from Gupy and InfoJobs, filtered by keywords.
2. Each listing is saved to PostgreSQL — duplicates are skipped by URL.
3. On demand, the AI analyzes the listing against your profile and returns a match score (0–100), matched/missing skills, and company tone.
4. The AI then generates a personalized application email in Brazilian Portuguese, tailored to the company tone and mentioning a relevant portfolio project.

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

```
src/main/java/com/juanperuzzo/job_hunter/
├── domain/                      ← pure Java, no framework dependencies
│   ├── model/                   → Job, EmailDraft, JobAnalysis, CompanyTone, EmailStatus,
│   │                              User, UserProfile
│   └── exception/               → ScraperException, AiException, JobNotFoundException,
│                                    InvalidCredentialsException, EmailAlreadyExistsException,
│                                    UserNotFoundException, ProfileNotConfiguredException,
│                                    AnalysisNotFoundException
│
├── application/                 ← use cases and ports
│   ├── port/in/                 → FetchJobsUseCase, AnalyzeJobUseCase, GenerateEmailUseCase,
│   │                              AuthUseCase, AuthResult
│   ├── port/out/                → JobRepository, ScraperPort, AiPort, UserRepository,
│   │                              PasswordHasher, TokenProvider, UserProfileRepository,
│   │                              EmailDraftRepository, JobAnalysisRepository
│   └── service/                 → FetchJobsService, AiAnalysisService, EmailGenerationService,
│   │                               AuthService, UserProfileService
│
├── infrastructure/              ← technical details (Spring, HTTP, DB, Security)
│   ├── scraper/                 → GupyScraper, InfoJobsScraper, CompositeScraper
│   ├── ai/                      → OpenRouterClient
│   ├── persistence/             → JobJpaRepository, JobPersistenceAdapter, UserEntity,
│   │                              UserJpaRepository, UserPersistenceAdapter, UserProfileEntity,
│   │                              UserProfileJpaRepository, UserProfilePersistenceAdapter,
│   │                              EmailDraftEntity, EmailDraftJpaRepository,
│   │                              EmailDraftPersistenceAdapter, JobAnalysisEntity,
│   │                              JobAnalysisJpaRepository, JobAnalysisPersistenceAdapter
│   ├── security/                → SecurityConfig, JwtTokenFilter, JwtTokenService,
│   │                              CurrentUserService
│   ├── scheduler/               → JobHunterScheduler
│   └── config/                  → AppConfig
│
├── web/                         ← REST controllers
│   ├── controller/              → JobController, EmailController, AuthController, ProfileController
│   ├── dto/                     → JobResponse, EmailDraftResponse, AuthRequest, AuthResponse,
│   │                              LoginRequest, ProfileRequest, ProfileResponse
│   └── exception/               → GlobalExceptionHandler
```

The dependency rule is strictly enforced: `domain` has no external dependencies, `application` depends only on `domain`, and `infrastructure`/`web` depend on `application`.

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
  secret: uma-chave-com-pelo-menos-32-caracteres-para-hmac
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
- The AI coding assistant (OpenCode + MiniMax M2.5) is guided by `AGENTS.md`, which documents the architecture, conventions, and workflow

---

## Project structure (docs)

```
docs/
├── architecture.md     ← package structure, schema, architectural decisions
├── prompts.md          ← all AI prompts versioned and documented
└── specs/
    ├── _template.md
    ├── fetch-jobs.md
    ├── deduplicate-jobs.md
    ├── gupy-scraper.md
    ├── analyze-job.md
    ├── generate-email.md
    ├── user-authentication.md
    ├── user-profile.md
    └── user-scoped-analysis.md
```

---

## Author

**Juan Peruzzo**
[juanperuzzo.is-a.dev](https://juanperuzzo.is-a.dev) · [GitHub](https://github.com/Uzzoper)
