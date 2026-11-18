# InfoJobs Detail Selectors — Analysis

## Finding

The InfoJobs detail page (e.g., `https://www.infojobs.com.br/vaga-de-desenvolvedor-junior-em-rio-grande-do-sul__11958660.aspx`) returns HTTP 200 in ~0.6s, but **none** of the expected CSS selectors exist:

- `[data-testid=job-description]` — 0 matches
- `.description` — 0 matches
- `.job-description` — 0 matches
- `[data-testid=company-name] a[href]` — 0 matches
- `.company a[href]` — 0 matches

The page has no `data-testid` attributes at all.

## What works

The page contains a `<script type="application/ld+json">` block with `@type: JobPosting` that carries:

```json
{
  "@type": "JobPosting",
  "title": "...",
  "description": "Área e especialização profissional: ... <full job description>",
  "hiringOrganization": { "name": "..." },
  "jobLocation": { "address": { "addressLocality": "..." } }
}
```

The `description` field is typically 2000-3000 chars — much larger than the card snippet (~200 chars).

## How the provider handles it

`InfoJobsProvider.extractDetailDescription()` tries 3 CSS selectors in order, then falls back to `JsonLdParser`. So the code path is:

1. Try selectors → empty
2. Try JSON-LD → 2633 chars found
3. Return JSON-LD description

The detail fetch **succeeds** — the JSON-LD is the real source.

## Implication

A 200 response with no CSS selectors is **not** a failure. Do not treat it as timeout or bot challenge.

## Location filter is the real bottleneck

The default `locations` config is `Remoto,Home office,Todo Brasil,Ponta Grossa,Paraná,PR,Curitiba`. Jobs from RS, MG, RJ, SP-city are silently dropped by the normalizer even though the title matches. This is why `totalSaved: 0` even when 524 cards were fetched.
