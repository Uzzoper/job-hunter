# Spec: Bot-Driven Company Enrichment (Hermes Agent Skill)

> **Type:** Hermes Agent skill + Python script + spec (no Java code in this issue)
> **Corresponding issue:** #32
> **Related issues:** #27 (hermes-agent-integration), #28 (async-company-enrichment), #31 (memory convention)
> **Depends on:** `hermes-agent-integration.md` (gateway shape, profile path, memory convention), `async-company-enrichment.md` (Java-side enrichment behavior)

---

## Goal

Provide the `jobhunter-bot` Hermes Agent with a company-research skill that the bot can invoke (via its shell tool) to extract structured metadata from a company website. This complements the existing Java-side `CompanyEnrichmentService` (issue #28) by enabling agentic, on-demand research driven by the bot during email drafting or user-initiated research — rather than requiring a Java-side HTTP call for every domain.

---

## Context

### What exists today

| Component | Behavior | Limitation |
|---|---|---|
| `CompanyEnrichmentService` (Java) | Batch `POST /api/jobs/enrich-emails` crawls distinct corporate domains via `CompanySiteEnrichmentPort` (Jsoup) | Requires explicit API call; no bot-side awareness; no tech-signal extraction |
| `CompanySiteEnrichmentPort` | Crawls contact pages, extracts emails via `CompanySiteEnricher` (mailto > text > de-obfuscated, robots, cache, rate limit) | Focused on emails only; no careers URL, no tech signals |
| Hermes bot (`jobhunter-bot`) | Handles email sending via chat-completions + himalaya | No company-research capability |

### How this complements the Java side

- The Java `CompanyEnrichmentService` remains the **primary batch enrichment path** (domain-deduped, portal-skip, EnrichmentResult). This spec does **not** replace or duplicate it.
- The bot skill is an **agentic sidecar**: invoked by the bot when it needs to research a company (e.g. before drafting a personalized email, or when the user asks "research CompanyX").
- The bot can write results to its memory (`~/.hermes/profiles/jobhunter-bot/memails/<domain>.json`), making them available for future interactions without re-crawling.
- The Java side can later read these memory files to bootstrap enrichment for domains already researched by the bot (follow-up issue, out of scope for #32).

---

## Expected behavior

### Scenario 1: bot invokes scraper on a company website
- **GIVEN** the bot is drafting an email for a job at "Acme Corp" with `companyWebsite = https://acme.com.br`
- **WHEN** the bot runs the company-scraper skill via its shell tool: `python3 ~/.hermes/profiles/jobhunter-bot/skills/company-scraper/scraper.py https://acme.com.br --company "Acme Corp"`
- **THEN** the script fetches the homepage with a 10 s timeout
- **AND** outputs JSON with `contactEmail`, `careersUrl`, `description`, and `signals`
- **AND** the bot uses the extracted data to personalize the email (e.g. mention tech stack, reference careers page)

### Scenario 2: portal domain is skipped without HTTP
- **GIVEN** the bot is asked to research a job hosted on `*.gupy.io`
- **WHEN** the scraper receives the URL
- **THEN** it returns `{"error": "portal_domain_skipped", "url": "..."}` immediately
- **AND** no HTTP request is made

### Scenario 3: fetch fails (timeout, 403, connection error)
- **GIVEN** a company website that is unreachable or blocks the request
- **WHEN** the scraper attempts to fetch
- **THEN** it returns `{"error": "fetch_failed"|"access_denied", "url": "..."}`
- **AND** the bot handles the error gracefully (reports to user or continues with available data)

### Scenario 4: no emails found on page
- **GIVEN** a company website with no `mailto:` links and no inline email addresses
- **WHEN** the scraper extracts emails
- **THEN** `contactEmail` is `null` in the output (not an error)
- **AND** `careersUrl` and `description` may still be populated

### Scenario 5: memory persistence
- **GIVEN** a successful scrape of `https://acme.com.br`
- **WHEN** the bot writes results to memory
- **THEN** the file `~/.hermes/profiles/jobhunter-bot/memails/acme.com.br.json` contains the full output JSON
- **AND** subsequent invocations for the same domain can read this file instead of re-fetching

### Scenario 6: careers link extraction with Portuguese keywords
- **GIVEN** a company website with a "Trabalhe Conosco" link at `/carreiras`
- **WHEN** the scraper parses the page
- **THEN** `careersUrl` is `https://acme.com.br/carreiras`
- **AND** Portuguese keywords like `vagas`, `carreiras`, `recrutamento` are all recognized

### Scenario 7: tech signal extraction
- **GIVEN** a company website mentioning "Spring Boot", "PostgreSQL", and "Docker" in its content
- **WHEN** the scraper scans the page body
- **THEN** `signals` contains `["spring boot", "postgresql", "docker"]`

---

## Business rules

1. **Python stdlib only** — the script uses `urllib` and `html.parser` (plus `re`). No `requests`, `bs4`, or any pip-installed dependency. Must be runnable on a bare Python 3.8+ installation.
2. **Portuguese regex patterns for BR content** — email and link extraction uses Portuguese keywords (`contato`, `e-mail`, `trabalhe conosco`, `vagas`, `carreiras`, `recrutamento`) as specified in `SKILL.md`.
3. **Portal-domain skip** — the script must not make HTTP requests to known portal domains (`*.gupy.io`, `*.infojobs.com.br`, `*.linkedin.com`, etc.), mirroring the Java `PortalDomains.isPortal()` guard.
4. **Domain-dedup rule** — when the bot researches multiple jobs at the same domain, it should write once to `memails/<domain>.json` and reuse the cached result (bot-side logic, not enforced by the script).
5. **Error convention** — the script always outputs valid JSON. Errors are represented as `{"error": "<code>", "url": "<input>"}` objects, never exceptions to stdout.
6. **No heavy deps install** — the install script copies files only; no `pip install` step.
7. **Memory sync convention** — bot memory files live at `~/.hermes/profiles/jobhunter-bot/memails/<domain>.json` (lowercase hostname). This follows the convention from issue #27/#31.

---

## Components

### 1. `skills/company-scraper/SKILL.md`

English skill documentation for the Hermes bot. Describes purpose, inputs, steps, output schema, and error conventions. Read by the bot's LLM to understand when and how to use the skill.

### 2. `skills/company-scraper/scraper.py`

Python 3 script (stdlib only) that:
- Fetches a URL with configurable timeout
- Extracts emails (mailto links + inline text, BR Portuguese patterns)
- Extracts careers/hiring page links (Portuguese keywords)
- Extracts company description (meta tags > og:description > first paragraph)
- Extracts tech-signal keywords (heuristic scan)
- Outputs structured JSON to stdout
- Runs standalone: `python3 scraper.py <url> [--company <name>]`

### 3. `scripts/install-bot-skills.sh`

Bash installer that:
- Copies `SKILL.md` + `scraper.py` to `~/.hermes/profiles/jobhunter-bot/skills/`
- Creates directories if they don't exist (profile may not exist yet)
- Creates `~/.hermes/profiles/jobhunter-bot/memails/` for memory files
- Sets executable permission on `scraper.py`
- Runs syntax verification (`python3 -m py_compile`, `bash -n`)

### 4. `docs/specs/bot-company-enrichment.md` (this file)

SDD spec documenting the skill's behavior, components, and relationship to existing enrichment infrastructure.

---

## Interface sketch (future Java integration — out of scope for #32)

The Java side could later read bot memory files to bootstrap enrichment:

```java
// Future output port addition (not in this issue)
public interface BotMemoryPort {
    Optional<CompanyResearchResult> readDomainMemory(String domain);
    void writeDomainMemory(String domain, CompanyResearchResult result);
}

// Future record (not in this issue)
public record CompanyResearchResult(
    String company,
    String website,
    String contactEmail,
    String careersUrl,
    String description,
    List<String> signals
) {}
```

This would allow `CompanyEnrichmentService` to check `memails/<domain>.json` before crawling, and a new `BotCompanyEnrichmentService` to delegate research to the bot for domains not yet in memory. **Explicitly out of scope for #32.**

---

## Acceptance criteria mapping

| # | Acceptance criterion | Delivered by |
|---|---|---|
| 1 | Skill documentation exists and is English-only | `skills/company-scraper/SKILL.md` |
| 2 | Python script runs standalone, stdlib only | `skills/company-scraper/scraper.py` |
| 3 | Script outputs structured JSON with all required fields | `scraper.py` — `scrape()` function output schema |
| 4 | Portuguese keywords recognized for BR content | `CAREERS_KEYWORDS` list + `EMAIL_RE` patterns |
| 5 | Portal domains skipped without HTTP | `_is_portal_domain()` guard |
| 6 | Install script copies skill to bot profile | `scripts/install-bot-skills.sh` |
| 7 | Install script is idempotent and safe to re-run | `set -euo pipefail`, `mkdir -p`, `cp` overwrite |
| 8 | Spec documents relationship to existing enrichment | This file — Context section + interface sketch |

---

## Memory sync convention

```
~/.hermes/profiles/jobhunter-bot/memails/<domain>.json
```

- `<domain>` = lowercase hostname (e.g. `acme.com.br`, `empresa.com`)
- File content = full output JSON from `scraper.py` (with optional `_cached_at` timestamp added by bot)
- Bot-side logic: check if file exists and is < 24h old before re-fetching
- Java-side (future): `BotMemoryPort.readDomainMemory(domain)` would check this path

---

## Out of scope

- **Java implementation** — `HermesAgenticClient` (agentic tool-calling wrapper), `BotCompanyEnrichmentService` (service reading bot memory), controller endpoint for triggering bot-side research. All deferred to a follow-up issue.
- **SMTP/MX email validation** — emails are extracted from HTML only, same as the Java enricher.
- **JavaScript-rendered content** — the script parses static HTML only. SPA company sites will return partial content. The Playwright-based LinkedIn scraper handles JS rendering for a different purpose.
- **Multi-user memory isolation** — the memory path is hardcoded to the single-user `jobhunter-bot` profile. Multi-user would need per-user memory namespaces.
- **Automated cron/scheduler** — the bot invokes the skill on-demand, not on a schedule.

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/bot-company-enrichment.md.

Step 1 — Implement the deliverables (no tests needed — Python script validated via py_compile):

  1. skills/company-scraper/SKILL.md — English skill doc per spec output schema
  2. skills/company-scraper/scraper.py — Python 3 stdlib only (urllib + re + html.parser)
     Functions: fetch(url, timeout), extract_emails(html), extract_careers_links(html, base),
     extract_description(html), scrape(url, company). CLI: python3 scraper.py <url>.
  3. scripts/install-bot-skills.sh — bash installer, set -euo pipefail, copy to bot profile,
     mkdir -p for dirs, chmod +x, verification listing
  4. docs/specs/bot-company-enrichment.md — this spec file

Step 2 — Validate:
  - python3 -m py_compile scraper.py
  - bash -n install-bot-skills.sh

Step 3 — Report files created + validation results.
```
