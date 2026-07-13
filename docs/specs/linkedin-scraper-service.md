# Spec: LinkedIn Scraper Service

> **Layer:** `infrastructure` (separate microservice)
> **Stack:** Node.js + Express + TypeScript + Playwright
> **Implementation:** `linkedin-scraper/src/`
> **Container:** `job-hunter-linkedin-scraper` (Docker)
> **Port:** 3000

---

## Context

LinkedIn blocks Jsoup-based scraping with a Bot Challenge (502 Bad Gateway). A dedicated microservice using Playwright (headless Chromium) bypasses this by presenting a realistic browser fingerprint (Chrome 129, pt-BR locale, `Accept-Language: pt-BR`).

The service is deployed as a **separate Docker container** because Playwright requires heavy Chromium dependencies that should not pollute the Spring Boot container.

**Architecture decision**: Playwright over Jsoup — the Jsoup-based `LinkedInProvider` remains as a lightweight fallback (`scraper.linkedin.mode=jsoup`), while the Playwright service is the default for production (`scraper.linkedin.mode=service`).

---

## Architecture

```
┌─────────────┐     HTTP      ┌──────────────────────────────────────┐
│ Spring Boot │ ──────────▶   │   LinkedIn Scraper Service (:3000)    │
│ (Java)      │               │                                      │
│             │               │  Express App Factory                 │
│ LinkedIn    │               │  ├── BrowserManager (singleton)      │
│ Scraper     │               │  ├── SearchScraper                   │
│ Client      │               │  ├── DetailScraper                   │
│ (RestClient)│               │  └── Routes (health + jobs)         │
└─────────────┘               │                                      │
                              │  Playwright (headless Chromium)      │
                              └──────────────────────────────────────┘
```

### Microservice Modules

| Module | File | Responsibility |
|--------|------|----------------|
| `BrowserManager` | `services/browser.ts` | Singleton — Chromium lifecycle (lazy init, health check, crash recovery, signal handlers) |
| SearchScraper | `scrapers/search.ts` | Navigate LinkedIn search results, extract job cards, scroll pagination (3 iterations), dedup by URL |
| DetailScraper | `scrapers/detail.ts` | Navigate job detail pages, extract description, criteria, requirements; login wall detection |
| Health router | `routes/health.ts` | `GET /health` — browser connectivity check |
| Jobs router | `routes/jobs.ts` | `GET /api/jobs`, `GET /api/jobs/:jobId` — validation + error mapping |
| App factory | `app.ts` | Express app with dependency injection for testability |

---

## Endpoints

### `GET /health`

Reports overall service health and browser connectivity.

**Response 200:**
```json
{
  "success": true,
  "data": {
    "status": "ok",
    "browser": "connected",
    "uptime": 1234
  }
}
```

### `GET /api/jobs?keywords=...&location=...`

Search LinkedIn for job listings matching keywords and optional location.

**Query Parameters:**

| Param | Required | Type | Description |
|-------|----------|------|-------------|
| `keywords` | Yes | string | Search terms (e.g., `java junior`) |
| `location` | No | string | Location filter (e.g., `Brazil`, `São Paulo`) |

**Response 200:**
```json
{
  "success": true,
  "data": [
    {
      "id": "4157639892",
      "title": "Desenvolvedor Java Júnior",
      "company": "TechCo Solutions",
      "location": "São Paulo, SP",
      "postedAt": "2026-07-01T14:00:00.000Z",
      "summary": ""
    }
  ]
}
```

### `GET /api/jobs/:jobId`

Fetch full details for a specific LinkedIn job.

**Path Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `jobId` | numeric string | LinkedIn job ID (e.g., `4157639892`) |

**Response 200:**
```json
{
  "success": true,
  "data": {
    "id": "4157639892",
    "title": "Desenvolvedor Java Júnior",
    "company": "TechCo Solutions",
    "location": "São Paulo, SP",
    "postedAt": "2026-07-01T14:00:00.000Z",
    "summary": "",
    "description": "<p>Estamos buscando um desenvolvedor...</p>",
    "requirements": ["Java 11+", "Spring Boot"],
    "seniority": "Estágio",
    "jobType": "Tempo integral",
    "jobFunction": "Tecnologia da informação",
    "industries": "Atividades dos serviços de tecnologia da informação"
  }
}
```

---

## Error Codes

| HTTP | Code | Trigger |
|------|------|---------|
| 400 | `VALIDATION_ERROR` | Missing or invalid parameters |
| 404 | `NOT_FOUND` | Job detail not accessible (login wall) |
| 429 | `RATE_LIMITED` | Bot challenge detected on LinkedIn |
| 503 | `SERVICE_UNAVAILABLE` | Browser not launched or crashed |
| 504 | `GATEWAY_TIMEOUT` | Navigation or scraping timed out |
| 500 | `INTERNAL_ERROR` | Unknown/unhandled error |

---

## Scraping Behavior

### Search Flow

1. Build search URL from keywords and optional location
2. Create isolated Playwright context with pt-BR locale and User-Agent Chrome 129
3. Navigate to LinkedIn search URL (`waitUntil: "domcontentloaded"`, 30s timeout)
4. Detect bot challenge (keyword check on page title + body text)
5. Wait for job card selector (`a.base-card__full-link[href*="/jobs/view"]`, 15s timeout)
6. Extract visible job cards from `.base-card` or `li` parent elements
7. **Pagination**: Scroll `.jobs-search-results-list` up to 3 times (3s delay between scrolls)
8. **Deduplication**: Track by URL (`Map<string, ...>`), skip duplicates across scrolls
9. Apply random delay (1-2s) between requests
10. Clean up: close page and context (even on error)

### Detail Flow

1. Apply human-like random delay (0.5-1s) before navigation
2. Create isolated Playwright context with pt-BR locale
3. Navigate to job detail URL (`waitUntil: "domcontentloaded"`, 30s timeout)
4. Wait for `.description__text` selector (15s timeout)
5. Detect bot challenge
6. **Login wall detection**:
   - First: check if `.description__text` is visible → accessible
   - Second: check if blocking modal/dialog is visible
   - Third: check body text for login keywords
7. Extract via `page.evaluate()`, including criteria with PT/EN label mapping:
   - `Nível de experiência` / `Seniority level` → seniority
   - `Tipo de emprego` / `Employment type` → workType
   - `Função` / `Job function` → jobFunction
   - `Setores` / `Industries` → industries
8. Parse requirements: regex `<li>` extraction with PT/EN requirement keyword detection

---

## BrowserManager Lifecycle

- **Singleton**: `BrowserManager.getInstance()` — single Chromium process per container
- **Lazy init**: Browser starts on first `launch()` call
- **Idempotent launch**: Safe to call multiple times
- **Crash recovery**: `ensureBrowser()` checks crash flag and browser connectivity
- **Health check**: Screenshot of `about:blank` to verify responsiveness
- **Signal handlers**: SIGTERM/SIGINT for graceful shutdown
- **Context isolation**: Each request creates a fresh `BrowserContext`

---

## Deployment

### Docker
```dockerfile
FROM mcr.microsoft.com/playwright:v1.61.1-noble
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY dist/ ./dist/
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD node -e "fetch('http://localhost:3000/health')..."
CMD ["node", "dist/index.js"]
```

### Docker Compose
```yaml
linkedin-scraper:
  build:
    context: ./linkedin-scraper
    dockerfile: Dockerfile
  container_name: job-hunter-linkedin-scraper
  ports: ["3000:3000"]
  restart: unless-stopped
```

---

## Business Rules

- LinkedIn guest access only — no login, no session cookies
- Each request creates a fresh browser context (isolated cookies)
- Realistic browser fingerprint: User-Agent Chrome 129, pt-BR locale
- `Accept-Language: pt-BR` header for localized content
- Human-like delays: 0.5-1s random delay before detail, 1-2s after search
- Scroll-based pagination (max 3 scrolls), stop if no new cards
- Dedup by URL within a single search
- Description returned as raw HTML (innerHTML)

---

## Expected behavior

### Scenario 1: successful search
- **GIVEN** LinkedIn search loads with job cards
- **WHEN** `GET /api/jobs?keywords=java+junior&location=Brazil`
- **THEN** returns 200 with `JobCard[]`, each with id/title/company/location/postedAt populated

### Scenario 2: empty search
- **GIVEN** no jobs match
- **WHEN** `GET /api/jobs?keywords=nonexistent`
- **THEN** returns 200 with empty `data` array

### Scenario 3: successful detail
- **GIVEN** valid LinkedIn job ID
- **WHEN** `GET /api/jobs/4157639892`
- **THEN** returns 200 with `JobDetail` including description, seniority, jobType, jobFunction, industries

### Scenario 4: login wall
- **GIVEN** job page requires login
- **WHEN** `GET /api/jobs/999`
- **THEN** returns 404 with `NOT_FOUND`

### Scenario 5: bot challenge
- **GIVEN** LinkedIn bot challenge page
- **WHEN** any endpoint
- **THEN** returns 429 with `RATE_LIMITED`

### Scenario 6: browser crash
- **GIVEN** Chromium crashed or not started
- **WHEN** any endpoint
- **THEN** returns 503 with `SERVICE_UNAVAILABLE`

### Scenario 7: timeout
- **GIVEN** LinkedIn slow to respond
- **WHEN** 30s timeout expires
- **THEN** returns 504 with `GATEWAY_TIMEOUT`

### Scenario 8: validation error
- **GIVEN** missing/empty keywords
- **WHEN** `GET /api/jobs`
- **THEN** returns 400 with `VALIDATION_ERROR`

---

## Error cases

| Situation | HTTP | Code |
|-----------|------|-------|
| Missing `keywords` | 400 | `VALIDATION_ERROR` |
| Non-numeric `jobId` | 400 | `VALIDATION_ERROR` |
| Bot challenge | 429 | `RATE_LIMITED` |
| Browser crash | 503 | `SERVICE_UNAVAILABLE` |
| Navigation timeout | 504 | `GATEWAY_TIMEOUT` |
| Login wall on detail | 404 | `NOT_FOUND` |
| Unhandled error | 500 | `INTERNAL_ERROR` |

---

## Test Coverage

| File | What it covers | Pattern |
|------|---------------|---------|
| `tests/app.test.ts` | Integration smoke tests | supertest + full app factory |
| `tests/routes/health.test.ts` | Health endpoint | Router with mock BrowserManager |
| `tests/routes/jobs.test.ts` | Validation + error mapping | Router with mock scrapers |
| `tests/services/browser.test.ts` | BrowserManager lifecycle | ESM import + Playwright mock |

**Total**: 52 tests. Run with `npm test`.

---

## Out of scope

- Does not authenticate to LinkedIn
- Does not bypass CAPTCHA or advanced bot challenges
- Does not use proxies or rotating IPs
- Does not scrape profiles or non-job content
- Does not use URL-based pagination
- Does not cache results

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/linkedin-scraper-service.md.
Key files:
- linkedin-scraper/src/app.ts
- linkedin-scraper/src/services/browser.ts
- linkedin-scraper/src/scrapers/search.ts
- linkedin-scraper/src/scrapers/detail.ts
- linkedin-scraper/src/routes/jobs.ts
- linkedin-scraper/src/types.ts

When modifying scrapers:
- Use Playwright `page.evaluate()` for scraping
- Test with real LinkedIn URLs
- Never use `networkidle` — use `domcontentloaded` + explicit `waitForSelector`
- Support both PT and EN label mappings for criteria
- No DOMParser in Node.js — use regex for requirements extraction
```
