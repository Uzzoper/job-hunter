# Skill: Report Generator

> Hermes Agent skill for on-demand weekly/monthly progress reports against the local SQLite database.

---

## Purpose

Generate weekly or monthly progress reports on demand from the local SQLite database (`./data/jobhunter.db`, override with `DB_URL`). The report measures emails actually sent (`email_drafts.status = 'SENT'` with a real `sent_at` timestamp) and optionally merges user-reported response and interview counts. Designed for the `jobhunter-bot` profile to answer questions such as "how many emails did I send this week?" or "give me my monthly report".

Complements the Java-side email pipeline (see `email-idempotency.md`) by giving the bot a read-only window into the same `email_drafts` table the sender writes to.

---

## When to use

- The user asks for a weekly or monthly progress report ("report this week", "monthly summary").
- The user asks how many applications/emails were sent in a period.
- The user wants a per-company breakdown of sent emails.
- The user wants a count of drafts still in flight.

---

## Inputs

| Parameter | Type | Required | Description |
|---|---|---|---|
| `user_id` | integer | yes | Authenticated user id. Every query filters by `email_drafts.user_id` / `job_analyses.user_id`. |
| `period` | string | no | `week` (last 7 rolling days, default) or `month` (current calendar month). |
| `responses` | integer | no | Optional user-reported reply count to merge into the report. The DB has no response columns. |
| `interviews` | integer | no | Optional user-reported interview count to merge into the report. The DB has no interview columns. |

---

## Steps

1. **Validate inputs** — confirm `user_id` exists in `users`; reject unknown ids or an invalid `period`.
2. **Open read-only** — connect with `file:./data/jobhunter.db?mode=ro` (never write).
3. **Count sent emails** — total sent, distinct jobs applied, distinct companies contacted from `email_drafts` joined to `jobs`, filtered by `user_id`, `status = 'SENT'`, and `sent_at` inside the window.
4. **Break down sent emails by company** — same join, `GROUP BY jobs.company`.
5. **Count drafts in flight** — `email_drafts.status` in (`PENDING`, `APPROVED`) generated inside the window.
6. **Merge external counts** — add `responses` / `interviews` when supplied by the user; print `—` when absent.
7. **Return** a markdown summary.

---

## Example SQL (real schema, read-only)

Window predicates: week → `e.sent_at >= datetime('now', '-7 days')`; month → `strftime('%Y-%m', e.sent_at) = strftime('%Y-%m', 'now')`.

```sql
-- Sent totals (weekly): emails sent, distinct jobs applied, distinct companies contacted
SELECT COUNT(*)                  AS emails_sent,
       COUNT(DISTINCT e.job_id)  AS jobs_applied,
       COUNT(DISTINCT j.company) AS companies_contacted
FROM email_drafts e
JOIN jobs j ON j.id = e.job_id
WHERE e.user_id = :user_id
  AND e.status = 'SENT'
  AND e.sent_at >= datetime('now', '-7 days');
```

```sql
-- Per-company breakdown (weekly)
SELECT j.company      AS company,
       COUNT(*)       AS sent,
       MIN(e.sent_at) AS first_sent,
       MAX(e.sent_at) AS last_sent
FROM email_drafts e
JOIN jobs j ON j.id = e.job_id
WHERE e.user_id = :user_id
  AND e.status = 'SENT'
  AND e.sent_at >= datetime('now', '-7 days')
GROUP BY j.company
ORDER BY sent DESC;
```

```sql
-- Drafts still in flight (generated but not yet sent)
SELECT COUNT(*) AS drafts_in_flight
FROM email_drafts
WHERE user_id = :user_id
  AND status IN ('PENDING', 'APPROVED')
  AND generated_at >= datetime('now', '-7 days');
```

Monthly report: reuse the same three queries replacing the window predicate with `strftime('%Y-%m', e.sent_at) = strftime('%Y-%m', 'now')`.

---

## Output format (markdown)

```markdown
## Report — last 7 days (user_id: <id>)
- Emails sent: 12
- Jobs applied: 11
- Companies contacted: 8
- Replies (user-reported): 2
- Interviews (user-reported): 0

Drafts in flight: 3 (PENDING/APPROVED)

| Company | Sent | First sent | Last sent |
|---|---|---|---|
| Acme | 4 | 2026-08-31 09:10 | 2026-09-04 17:42 |
| Beta | 3 | 2026-09-01 08:30 | 2026-09-03 14:05 |
```

---

## Error conventions

| Situation | Behavior |
|---|---|
| DB file missing or not readable | Return `{"error": "database_unavailable", "path": "./data/jobhunter.db"}` |
| `user_id` not found in `users` | Return `{"error": "unknown_user", "user_id": <id>}` |
| Invalid `period` value | Return `{"error": "invalid_period", "period": "<value>"}` |
| `responses` / `interviews` not provided | Show as `—` in the report (not an error; schema has no reply tracking) |
| Zero rows in the window | Valid result: return the report with zeros, not an error |

---

## Non-goals

- **Never modify the database** — the skill is strictly read-only (`mode=ro` URI); no inserts, updates, or deletes, and no use of the JPA-backed application.
- **No email sending** — the skill reports on sent emails; it never triggers sends.
- **No browser automation** — no Playwright/JSoup scraping, no web fetching.
- Does not track replies or interviews — those numbers are user-reported inputs, not DB columns.

---

## Guardrail — user scoping

`jobs` is a global pool (it has no `user_id` column). Progress is per-user, so every user query must join through `email_drafts` (or `job_analyses`) and filter `email_drafts.user_id = :user_id`. Never report raw `jobs` counts as user progress.

---

## Limitations

- Only sent emails are measured from the database; reply and interview counts depend on user input.
- Period semantics are fixed: `week` = trailing 7 days, `month` = current calendar month.
- `email_drafts` keeps one row per (job, user); repeated sends to the same job cannot be distinguished (see the `status = 'SENT'` idempotency index in V4).