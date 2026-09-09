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

## Extension: enriched business signals (issue #36)

> **Type:** Python extractors + CLI flag + tests + docs (no Java code)
> **Corresponding issue:** #36
> **Depends on:** the base skill from #32 (same file layout, stdlib-only policy)
> **Tests:** `skills/company-scraper/scraper_test.py` (plain unittest, pytest-compatible, RED→GREEN)

### Goal

Extend the company-scraper skill with semantic business signals that help the bot (and the future Java enrichment path) build a richer company profile before drafting application emails: workplace culture, product/offering, team size, funding stage, and recent news.

### New extractors (`skills/company-scraper/scraper.py`)

| Function | Returns | Portuguese patterns |
|---|---|---|
| `extract_culture(text)` | comma-separated string or `None` | `remoto`, `híbrido`, `presencial`, `home office`, `clean code`, `TDD`, `metodologias ágeis`, `vale refeição`/`vale alimentação`, `plano de saúde`, ... |
| `extract_products(text)` | first product phrase or `None` | `plataforma de X`, `soluções em X`, `oferecemos X`, `nossos produtos (de/em) X` |
| `extract_team_size(text)` | `"50-200"` style string or `None` | `50-200 funcionários/colaboradores`, `mais de N pessoas`, `time de N pessoas` |
| `extract_funding(text)` | stage string or `None` | `Seed`, `Série A/B/C`, `Series A/B/C`, `Bootstrapped`, `aporte`, `captação` |
| `extract_recent_news(text)` | semicolon-joined snippets or `None` | `captação`, `aquisição`, `lançamento`, `expansão` + year `2025`/`2026` |

### New flow (secondary LinkedIn source)

- CLI gains `--linkedin-url <url>`: fetch the LinkedIn company page as a secondary source.
- `merge_short_from_linkedin()` fills gaps from the primary URL only (company name, description, tech signals); it never overrides primary values.
- If the LinkedIn fetch fails (portal guard, timeout, 403), the primary result is returned unchanged and `linkedinUrl` still holds the argument value. The accept-at-minimum behavior required by #36.

### Output schema additions

```json
{
  "...": "existing #32 fields",
  "culture": "<comma-separated culture/benefit signals or null>",
  "products": "<first product phrase or null>",
  "teamSize": "<'50-200' | '100+' | '25' | null>",
  "funding": "<Seed | Série A/B/C | Series A/B/C | Bootstrapped | Aporte | Captação | null>",
  "recentNews": "<semicolon-joined snippets or null>",
  "linkedinUrl": "<linkedin URL argument or null>"
}
```

### Test plan (TDD)

`skills/company-scraper/scraper_test.py` (plain `unittest`, pytest-compatible, inline HTML fixtures):

- `ExtractCultureTests` — multiple signals comma-joined, single signal, none → `None`
- `ExtractProductsTests` — all four BR patterns, none → `None`
- `ExtractTeamSizeTests` — range, `mais de N`, `time de N`, `colaboradores` variant, none
- `ExtractFundingTests` — Seed, `Série/Series` variants, Bootstrapped, Aporte, Captação, none
- `ExtractRecentNewsTests` — keyword + year combos, semicolon joining, no-year → `None`
- `ScrapeOutputSchemaTests` — `scrape_from_html()` output contains all #36 fields; `linkedinUrl` null when not provided
- `CliSmokeTests` — usage message on no-URL, `parse_args()` handles `--linkedin-url`

### Business rules (additions)

1. **Stdlib only preserved** — new extractors use `re` only; no new imports beyond the standard library.
2. **First-match semantics** — `extract_products` returns the first match in document order (matching the #36 wording "first product match").
3. **Recency filter** — a news keyword only counts when the snippet contains 2025 or 2026; no year → `None`.
4. **LinkedIn merge is best-effort** — fetch failures never abort the primary result.
5. **No Java in #36** — extractors run on the bot side (Python); Java integration remains a follow-up.

### Acceptance criteria mapping (#36)

| Criterion | Delivered by |
|---|---|
| `extract_culture` → comma-separated string or null | `scraper.py` + `ExtractCultureTests` |
| `extract_products` → first product match or null | `scraper.py` + `ExtractProductsTests` |
| `extract_team_size` → `"50-200"` style or null | `scraper.py` + `ExtractTeamSizeTests` |
| `extract_funding` → stage or null | `scraper.py` + `ExtractFundingTests` |
| `extract_recent_news` → semicolon-joined or null | `scraper.py` + `ExtractRecentNewsTests` |
| `--linkedin-url` CLI arg | `parse_args` + `merge_short_from_linkedin` + `CliSmokeTests` |
| All new fields in output JSON | `scrape_from_html` output schema + `ScrapeOutputSchemaTests` |
| `scraper_test.py` with fixtures + CLI smoke test | `scraper_test.py` (33 tests) |

---

## Memory sync convention

```
~/.hermes/profiles/jobhunter-bot/memails/<domain>.json
```

- `<domain>` = lowercase hostname (e.g. `acme.com.br`, `empresa.com`)
- File content = full output JSON from `scraper.py` (with optional `_cached_at` timestamp added by bot; includes all #36 fields)
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
