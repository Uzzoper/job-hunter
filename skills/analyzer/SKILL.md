# Skill: Analyzer

> Hermes Agent skill for pattern analysis of analyzed jobs against the local SQLite database.

---

## Purpose

Find patterns across a user's analyzed jobs: top-performing stacks, roles, and companies, plus a work-model split. Metrics are grounded in real schema columns: `job_analyses.match_score`, `jobs.contact_email`, and the send conversion visible in `email_drafts`. Designed for the `jobhunter-bot` profile to answer questions like "which stacks/roles/companies score best?" or "which companies should I prioritize?".

The database stores no reply tracking, so wherever the user asks for "response rate" the skill returns schema-backed proxies (`with_contact` share and `emails_sent` conversion) and labels them as proxies rather than inventing reply columns.

---

## When to use

- The user asks which stacks, roles, or companies perform best based on match score.
- The user asks which companies have the highest send conversion or best contact availability.
- The user asks how performance splits by work model (remote/hybrid/onsite).

---

## Inputs

| Parameter | Type | Required | Description |
|---|---|---|---|
| `user_id` | integer | yes | Authenticated user id. Filters `job_analyses.user_id`. |
| `limit` | integer | no | Max rows per table (default 10). |
| `min_score` | integer | no | Match-score floor, e.g. 70 (default 0). Uses `job_analyses.match_score`. |
| `since` | date | no | Only analyses with `analyzed_at >= :since` (default `datetime('now', '-90 days')`). |
| `company` | string | no | Restrict to one company via `jobs.company`. |

---

## Steps

1. **Validate inputs** — confirm `user_id` exists in `users`.
2. **Open read-only** — connect with `file:./data/jobhunter.db?mode=ro` (never write).
3. **Top companies** — group `job_analyses` joined to `jobs` by `company`, ordered by sent count then average score.
4. **Top roles** — same shape, grouped by `jobs.title`.
5. **Top stacks** — explode the `job_analyses.matched_skills` JSON array with `json_each` (SQLite ≥ 3.38; the bundled sqlite-jdbc ships 3.4x, so this is available at runtime).
6. **Work-model split** — classify `jobs.description` keywords (there is no work-model column in the schema).
7. **Return** markdown tables plus a labeled note on the response-rate proxy.

---

## Example SQL (real schema, read-only)

### Top companies

```sql
SELECT j.company                                                  AS company,
       COUNT(*)                                                   AS analyzed,
       ROUND(AVG(a.match_score), 1)                               AS avg_score,
       SUM(CASE WHEN j.contact_email IS NOT NULL THEN 1 ELSE 0 END) AS with_contact,
       COUNT(s.id)                                                AS emails_sent
FROM job_analyses a
JOIN jobs j ON j.id = a.job_id
LEFT JOIN email_drafts s
       ON s.job_id = a.job_id
      AND s.user_id = a.user_id
      AND s.status = 'SENT'
WHERE a.user_id = :user_id
  AND a.match_score >= :min_score
  AND a.analyzed_at >= datetime('now', '-90 days')
GROUP BY j.company
ORDER BY emails_sent DESC, avg_score DESC
LIMIT :limit;
```

### Top stacks (JSON array in `matched_skills`)

```sql
SELECT skill.value                                                AS stack,
       COUNT(DISTINCT a.job_id)                                   AS analyzed,
       ROUND(AVG(a.match_score), 1)                               AS avg_score,
       SUM(CASE WHEN j.contact_email IS NOT NULL THEN 1 ELSE 0 END) AS with_contact,
       COUNT(s.id)                                                AS emails_sent
FROM job_analyses a
JOIN jobs j ON j.id = a.job_id
JOIN json_each(a.matched_skills) skill
LEFT JOIN email_drafts s
       ON s.job_id = a.job_id
      AND s.user_id = a.user_id
      AND s.status = 'SENT'
WHERE a.user_id = :user_id
  AND a.match_score >= :min_score
GROUP BY skill.value
ORDER BY emails_sent DESC, avg_score DESC
LIMIT :limit;
```

### Top roles

```sql
SELECT j.title                                                    AS role,
       COUNT(*)                                                   AS analyzed,
       ROUND(AVG(a.match_score), 1)                               AS avg_score,
       SUM(CASE WHEN j.contact_email IS NOT NULL THEN 1 ELSE 0 END) AS with_contact,
       COUNT(s.id)                                                AS emails_sent
FROM job_analyses a
JOIN jobs j ON j.id = a.job_id
LEFT JOIN email_drafts s
       ON s.job_id = a.job_id
      AND s.user_id = a.user_id
      AND s.status = 'SENT'
WHERE a.user_id = :user_id
  AND a.match_score >= :min_score
GROUP BY j.title
ORDER BY emails_sent DESC, avg_score DESC
LIMIT :limit;
```

### Work-model split (no column exists; keyword classification of `jobs.description`)

```sql
SELECT CASE
         WHEN lower(j.description) LIKE '%remoto%'     OR lower(j.description) LIKE '%remote%'    THEN 'remote'
         WHEN lower(j.description) LIKE '%hibrido%'    OR lower(j.description) LIKE '%hybrid%'    THEN 'hybrid'
         WHEN lower(j.description) LIKE '%presencial%' OR lower(j.description) LIKE '%onsite%'    THEN 'onsite'
         ELSE 'unspecified'
       END                            AS work_model,
       COUNT(*)                        AS analyzed,
       ROUND(AVG(a.match_score), 1)    AS avg_score,
       SUM(CASE WHEN j.contact_email IS NOT NULL THEN 1 ELSE 0 END) AS with_contact
FROM job_analyses a
JOIN jobs j ON j.id = a.job_id
WHERE a.user_id = :user_id
  AND a.match_score >= :min_score
GROUP BY work_model
ORDER BY analyzed DESC;
```

---

## Output format (markdown)

```markdown
## Pattern analysis (user_id: <id>, since last 90 days)

### Top roles
| Role | Analyzed | Avg score | With contact | Emails sent |
|---|---|---|---|---|
| Junior Java Developer | 14 | 78.4 | 11 | 7 |

### Top companies
| Company | Analyzed | Avg score | With contact | Emails sent |
|---|---|---|---|---|
| Acme | 6 | 82.0 | 6 | 5 |

### Top stacks
| Stack | Analyzed | Avg score | With contact | Emails sent |
|---|---|---|---|---|
| Java | 18 | 76.2 | 14 | 9 |
| Spring | 15 | 77.9 | 12 | 8 |

### Work model
| Work model | Analyzed | Avg score | With contact |
|---|---|---|---|
| remote | 20 | 79.1 | 16 |
| hybrid | 8 | 71.0 | 5 |
| unspecified | 4 | 65.5 | 3 |

> Note: `with_contact/analyzed` is a contact-availability proxy and `emails_sent` is send
> conversion. True reply rates are not in the schema; ask the user if they want to merge
> a user-reported response count.
```

---

## Error conventions

| Situation | Behavior |
|---|---|
| DB file missing or not readable | Return `{"error": "database_unavailable", "path": "./data/jobhunter.db"}` |
| `user_id` not found in `users` | Return `{"error": "unknown_user", "user_id": <id>}` |
| `matched_skills` holds invalid JSON | Return `{"error": "invalid_skill_json", "job_analysis_id": <id>}` |
| SQLite < 3.38 (no `json_each`) | Fall back to parsing `matched_skills` with Python's `json` module; do not fail silently |
| `description` is NULL for a row | It lands in the `unspecified` work-model bucket (not an error) |

---

## Non-goals

- **Never modify the database** — strictly read-only (`mode=ro` URI); no inserts, updates, deletes, and no re-scoring of `job_analyses`.
- **No email sending** — the skill reports send conversion; it never triggers sends.
- **No browser automation** — no scraping, no web fetching, no Playwright.
- Does not compute true reply rates — the schema has no response-tracking table; proxies are always labeled.

---

## Guardrail — user scoping

All analysis is per-user: filter `job_analyses.user_id = :user_id` in every query and join `jobs` only through `job_analyses.job_id`. Never present global `jobs` row counts as a per-user metric.

---

## Limitations

- Work model is inferred from `jobs.description` keywords, not a persisted column (`work_model` exists only as a transient field in the InfoJobs provider).
- `match_score` is a heuristic AI score (0–100), not an application outcome.
- Stacks are tags from `matched_skills`; they reflect the job listing's keywords, not validated experience on the candidate side.
- Every `email_drafts` row is scoped to a `user_id`, so conversion numbers are per-user by construction.