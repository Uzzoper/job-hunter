# Provider Email Rate Analysis

> Diagnostic technique to determine **where to focus scraping/enrichment effort**.

---

## SQL Query

```python
import sqlite3
conn = sqlite3.connect('data/jobhunter.db')
cur = conn.execute("""
  SELECT source,
         COUNT(*) AS total,
         SUM(CASE WHEN contact_email IS NOT NULL AND contact_email != '' THEN 1 ELSE 0 END) AS with_email,
         ROUND(100.0 * SUM(CASE WHEN contact_email IS NOT NULL AND contact_email != '' THEN 1 ELSE 0 END) / COUNT(*), 1) AS email_rate
  FROM jobs
  GROUP BY source
  ORDER BY email_rate DESC
""")
for row in cur:
    print(f"{row[0]:<12} total={row[1]:>5}  email={row[2]:>4}  rate={row[3]:>5}%")
conn.close()
```

## Interpretation

| Pattern | Meaning | Action |
|---|---|---|
| High rate, low volume | Provider yields emails but few jobs | Increase scrape volume (more pages, more keywords) |
| Low rate, high volume | Provider yields jobs but few emails | Check `enrich-emails` endpoint; high `skippedPortal` = `company_website` bug |
| Zero rate | Either no jobs fetched or no email extraction | Investigate scraper config or email source |
| Missing provider | Provider not in DB at all | Run scraper for that provider |

## Observed data (Oct 2026)

| Source | Total | With email | Rate | Email source |
|---|---|---|---|---|
| LinkedIn | 209 | 9 | 4.3% | Detail page metadata (NOT description) |
| Gupy | 3659 | 92 | 2.5% | Description (but `company_website` bug limits enrichment) |
| Greenhouse | 1297 | 361 | 27.8% | Job metadata (but many are compliance emails) |
| Ashby | 284 | 10 | 3.5% | Job metadata |
| GitHub | 57 | 14 | 24.6% | Job metadata |

**LinkedIn has the highest real email rate** but lowest volume.
**Greenhouse has 27.8% email rate** but most are compliance/accessibility emails.
**InfoJobs is NOT an email source** — 0 emails from 522 fetched jobs.

## The `skippedPortal` / `company_website` bug

**Symptom**: `POST /api/jobs/enrich-emails` returns `skippedPortal: 49` of 50 checked.

**Cause**: The Gupy scraper persists the portal URL (`*.gupy.io`) as `company_website`. Since `PortalDomains.isPortal()` matches `gupy.io`, the enrichment skips these jobs.

**Detection**:
```python
cur = conn.execute("""
  SELECT COUNT(*) FROM jobs 
  WHERE source='gupy' 
    AND company_website IS NOT NULL AND company_website != ''
    AND (company_website LIKE '%gupy.io%' OR company_website LIKE '%gupy.com.br%')
""")
# Result: ~802 of 806 (99.5%) have portal URL instead of real company site
```

**Fix**: `GupyProvider` must extract real company website from detail page (JSON-LD or selector), or leave `company_website = null`.

## LinkedIn email source

Emails come from **structured data on the detail page**, not the description text.

**Verification**:
```python
cur = conn.execute("""
  SELECT COUNT(*) FROM jobs 
  WHERE source='linkedin' 
    AND (contact_email IS NULL OR contact_email='') 
    AND description LIKE '%@%'
""")
# Result: 0 — descriptions are NOT a source of emails for LinkedIn
```

## Compliance / generic email trap

Many `contact_email` values in the DB are **not real recruiter inboxes** — they are
compliance, accessibility, or switchboard addresses that appear on every posting
of the company. Classify by the local part before treating as an apply channel:

| Local part pattern | What it is | Action |
|---|---|---|
| `candidate_accessibility@`, `accommodations@`, `hiringaccommodation@` | ADA/accommodation compliance | Reject — not recruiting |
| `more@`, `handbook@`, `listed@` | Generic company inbox | Reject — not recruiting |
| `privacidade@`, `dpo@`, `lgpd@` | Data protection officer | Reject — sending CV there is an error |
| `contato@`, `comercial@` | Commercial/switchboard | Reject — not recruiting |
| `vaga-<id>@vagas.<ats>` | ATS round-robin | Reject — not a real inbox |
| `recruiting@`, `recrutamento@`, `jobs@`, `talentacquisition@` | Real recruiter | Accept |
| Personal name (`giovani@`, `christianedepaula@`) | Real recruiter | Accept |

**Detection query:**
```python
import sqlite3
conn = sqlite3.connect('data/jobhunter.db')
cur = conn.execute("""
  SELECT contact_email, COUNT(*) as n FROM jobs
  WHERE contact_email IS NOT NULL AND contact_email != ''
  GROUP BY contact_email HAVING n > 5 ORDER BY n DESC
""")
for email, n in cur:
    print(f'  {n:4d}x  {email}')
conn.close()
```

In one 486-email batch, only ~30 survived all filters (most are compliance emails).
Report the filter outcome as a count and the discard reason per group, never the raw list.

## Generic template trap (score < 60)

`POST /api/jobs/{id}/email` returns HTTP 200 with a **placeholder body** when the
job's `matchScore` is below `email.standard-template.min-match-score` (60 in
`application.yaml`). The stub has subject `"Subject: [Aplicação externa]"` and body
`"Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado."`
with `status: SENT`. The 200 and the `SENT` are the stub's own fields — a
status-only check reads as success.

**Always print `subject` and `body` before reporting a draft as generated.**
Do not lower the threshold to force a draft on a weak match — the gate is the
quality control. If the user wants an email for a sub-60 job, hand-write it
(in the posting's language) and show it before sending.

## Related endpoints

- `POST /api/jobs/enrich-emails` — returns `{checked, enriched, skippedPortal, failed}`
- High `skippedPortal` = portal jobs skipped (already have email or `company_website` is a portal URL)
- `POST /api/jobs/fetch/infojobs` — returns `{totalFetched, totalSaved, totalWithEmail, perProvider:[...]}`
