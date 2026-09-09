# Skill: Company Scraper

> Hermes Agent skill for semantic company research on Brazilian job listings.

---

## Purpose

Extract structured company metadata from a given website. Designed for the `jobhunter-bot` profile to enrich job records with contact emails, careers pages, company descriptions, and technology signals — especially useful for real corporate domains where provider scrapers (Gupy, InfoJobs) do not provide this information.

Complements the Java-side `CompanyEnrichmentService` (see `async-company-enrichment.md`) by running as an agentic tool inside the Hermes bot: the bot can invoke the scraper script when asked to research a company, parse the result, and optionally write findings to the memory file (`~/.hermes/profiles/jobhunter-bot/memails/<domain>.json`).

---

## When to use

- A job listing has a `companyWebsite` pointing to a real corporate domain (not a job portal like `*.gupy.io` or `*.infojobs.com.br`).
- The job's `contactEmail` is null and the Java enrichment batch has not yet covered this domain.
- The bot is asked to research a company before drafting an application email (e.g. "research CompanyX before writing the email").
- Manual ad-hoc research by the user via chat.

---

## Inputs

| Parameter | Type | Required | Description |
|---|---|---|---|
| `url` | string | yes | Full company website URL (e.g. `https://company.com.br`) |
| `company` | string | no | Company name hint (helps disambiguate contact info) |
| `job_context` | string | no | Brief job description or role (helps select relevant careers links) |

---

## Steps

1. **Fetch** the company homepage (GET request with configurable timeout, default 10 s).
2. **Extract contact emails** — scan the HTML for `mailto:` links, text matching email patterns, and common BR Portuguese labels (`contato`, `e-mail`, `fale conosco`).
3. **Extract careers links** — find anchors with Portuguese keywords (`trabalhe conosco`, `vagas`, `carreiras`, `recrutamento`, `cultura`) and normalize to absolute URLs.
4. **Extract company description** — look for `<meta name="description">`, Open Graph description, or first substantial `<p>` block in the page body.
5. **Extract tech signals** — scan for technology keywords in the page content (frameworks, languages, tools) as lightweight tech-stack hints.
6. **Extract tech signals** — scan for technology keywords in the page content (frameworks, languages, tools) as lightweight tech-stack hints.
7. **Extract culture signals** — workplace regime and benefits (remote/hybrid/on-site, clean code, TDD, agile methodologies, meal vouchers, health plan).
8. **Extract business signals** — first product/offering phrase, team size, funding stage, and recent-news snippets (funding, acquisition, launches, expansion in 2025/2026).
9. **Optional LinkedIn merge** — when `--linkedin-url` is given, fetch the LinkedIn company page as a secondary source and fill any gaps (company name, description, tech signals). If the LinkedIn fetch fails, results from the primary URL are returned unchanged.
10. **Return** a JSON object with the extracted fields.

---

## Output JSON schema

```json
{
  "company": "<string, company name or null>",
  "website": "<string, input URL>",
  "contactEmail": "<string email or null>",
  "careersUrl": "<string absolute URL or null>",
  "description": "<string, company description or null>",
  "signals": ["<string>", "..."],
  "culture": "<string, comma-separated culture/benefit signals or null>",
  "products": "<string, first product/offering phrase or null>",
  "teamSize": "<string like '50-200' or '100+' or '25' or null>",
  "funding": "<string: Seed | Série A/B/C | Series A/B/C | Bootstrapped | Aporte | Captação | null>",
  "recentNews": "<string, semicolon-joined news snippets or null>",
  "linkedinUrl": "<string linkedin URL or null>"
}
```

### Field semantics

| Field | Meaning |
|---|---|
| `company` | Best-guess company name extracted from the page (from `<title>`, `<h1>`, or Open Graph). Null if not found. |
| `website` | The input URL, passed through. |
| `contactEmail` | First non-null email found. Null if no email discovered. |
| `careersUrl` | First absolute careers/hiring page URL. Null if none found. |
| `description` | Short company description (max ~500 chars). Null if not found. |
| `signals` | Array of technology keywords detected on the page. Empty array if none found. |
| `culture` | Comma-separated workplace/benefit signals: `remoto`, `híbrido`, `presencial`, `home office`, `clean code`, `tdd`, `metodologias ágeis`, `vale refeição`, `vale alimentação`, `plano de saúde`. Null if none found. |
| `products` | First product/offering phrase matching BR patterns (`plataforma de X`, `soluções em X`, `oferecemos X`, `nossos produtos`). Null if none found. |
| `teamSize` | Normalized team size: `"50-200"` (range), `"100+"` (`mais de N pessoas`), `"25"` (`time de N`). Null if not found. |
| `funding` | Funding stage: `Seed`, `Série A/B/C`, `Series A/B/C`, `Bootstrapped`, `Aporte`, `Captação`. First match wins. Null if none found. |
| `recentNews` | Semicolon-joined snippets mentioning funding/acquisition/launch/expansion near 2025/2026. Null if none qualify. |
| `linkedinUrl` | The `--linkedin-url` argument value (passed through). Null when not provided. |

---

## Error conventions

| Situation | Behavior |
|---|---|
| HTTP timeout or connection error | Return `{"error": "fetch_failed", "url": "<input>"}` |
| HTTP 403 / robots disallow | Return `{"error": "access_denied", "url": "<input>"}` |
| Portal domain detected (`*.gupy.io`, `*.infojobs.com.br`, etc.) | Return `{"error": "portal_domain_skipped", "url": "<input>"}` |
| No emails found | `contactEmail` is `null` (not an error) |
| Empty or unreadable page | Return `{"error": "empty_response", "url": "<input>"}` |

---

## Usage examples

### CLI (standalone)

```bash
python3 skills/company-scraper/scraper.py https://company.com.br
python3 skills/company-scraper/scraper.py https://company.com.br --company "Company Name"
python3 skills/company-scraper/scraper.py https://company.com.br \
  --linkedin-url https://www.linkedin.com/company/company-name
```

The `--linkedin-url` flag adds the LinkedIn company page as a secondary source. Its fields are merged in best-effort (company name, description, tech signals) only where the primary URL left them empty; a failed LinkedIn fetch is non-fatal and the primary result is returned unchanged. The value is always echoed back in the output's `linkedinUrl` field.

### Inside Hermes bot

The bot can invoke the script via its shell tool and parse the JSON output to populate memory or report findings to the user.

---

## Memory sync convention

Results may be persisted by the bot to:

```
~/.hermes/profiles/jobhunter-bot/memails/<domain>.json
```

where `<domain>` is the lowercase hostname of the company website (e.g. `company.com.br`). This follows the convention established in issue #27 and referenced in `hermes-agent-integration.md`.

---

## Limitations

- Static HTML parsing only — JavaScript-rendered SPAs will return partial content (useful for server-rendered corporate sites common in Brazil).
- No browser automation (complements, does not replace, the Playwright-based LinkedIn scraper).
- No SMTP/MX validation — emails are extracted from HTML only.
- Tech-signal extraction is heuristic; it surfaces keywords, not a validated stack audit.
- Culture/products/team-size/funding/news extraction is regex-based and best-effort; it may miss signals in heavily nested or JS-rendered content.
