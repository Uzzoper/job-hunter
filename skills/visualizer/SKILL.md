# Skill: Visualizer

> Hermes Agent skill for application-funnel charts against the local SQLite database, using only `sqlite3` (stdlib) and `matplotlib`.

---

## Purpose

Render a funnel chart of a user's application pipeline from the local SQLite database (`./data/jobhunter.db`). The funnel stages are computed from real schema columns: analyzed (`job_analyses.analyzed_at`), drafted (`email_drafts.generated_at`), and sent (`email_drafts.sent_at` with `status = 'SENT'`). Designed for the `jobhunter-bot` profile to answer "show me my application funnel" with a single static PNG.

---

## When to use

- The user asks to visualize their application funnel or pipeline.
- The user asks for a chart of analyzed vs. drafted vs. sent jobs.
- The user wants a quick visual on where the pipeline drops off.

---

## Inputs

| Parameter | Type | Required | Description |
|---|---|---|---|
| `user_id` | integer | yes | Authenticated user id. Filters `job_analyses` and `email_drafts`. |
| `since` | date | no | Window start; default `datetime('now', '-90 days')`. |
| `output` | string | no | Output image path (default `funnel.png`). |
| `show_rejected` | boolean | no | Include `email_drafts.status = 'REJECTED'` as an extra stage (default false). |

---

## Dependencies

- Python ≥ 3.9: `sqlite3` is in the standard library (read-only via the `mode=ro` URI).
- `matplotlib` — one-time install:

```bash
pip install matplotlib
```

If matplotlib is not importable, return `{"error": "matplotlib_missing", "hint": "pip install matplotlib"}` — do not attempt other plotting libraries.

---

## Steps

1. **Validate inputs** — confirm `user_id` exists in `users`.
2. **Open read-only** — `sqlite3.connect("file:./data/jobhunter.db?mode=ro", uri=True)` (never write).
3. **Compute stage counts** (real columns):
   - analyzed: `job_analyses` rows with `analyzed_at >= :since`
   - drafted: `email_drafts` rows with `status IN ('PENDING','APPROVED','SENT')` and `generated_at >= :since`
   - sent: `email_drafts` rows with `status = 'SENT'` and `sent_at >= :since`
   - rejected (optional): `email_drafts` rows with `status = 'REJECTED'`
4. **Render** a horizontal bar funnel with matplotlib (keep stages at 0 when empty — the funnel must show where the pipeline breaks).
5. **Return** the output path and the stage counts.

---

## Example script (minimal)

```python
import sqlite3
import matplotlib.pyplot as plt

DB = "file:./data/jobhunter.db?mode=ro"
USER_ID = 1  # authenticated user id, supplied at call time

con = sqlite3.connect(DB, uri=True)

def count(sql):
    return con.execute(sql, (USER_ID,)).fetchone()[0]

stages = [
    ("Analyzed", count(
        "SELECT COUNT(*) FROM job_analyses "
        "WHERE user_id=? AND analyzed_at >= datetime('now','-90 days')")),
    ("Drafted", count(
        "SELECT COUNT(*) FROM email_drafts "
        "WHERE user_id=? AND status IN ('PENDING','APPROVED','SENT') "
        "AND generated_at >= datetime('now','-90 days')")),
    ("Sent", count(
        "SELECT COUNT(*) FROM email_drafts "
        "WHERE user_id=? AND status='SENT' "
        "AND sent_at >= datetime('now','-90 days')")),
]

labels = [s[0] for s in stages]
values = [s[1] for s in stages]

fig, ax = plt.subplots(figsize=(6, 4))
ax.barh(labels, values, color="#4c72b0")
ax.set_title("Application funnel")
ax.set_xlabel("Jobs")
for i, v in enumerate(values):
    ax.text(v, i, f" {v}", va="center")
fig.tight_layout()
fig.savefig("funnel.png")
print("wrote funnel.png:", dict(stages))
```

---

## Output conventions

- Save the chart to `output` (default `funnel.png`) and return the path plus the counts:

```markdown
Funnel saved to funnel.png
- Analyzed: 30
- Drafted: 18
- Sent: 12
```

- If the user supplies a response/interview number, mention that matplotlib charts only DB-tracked stages; replies are not stored in the schema.

---

## Error conventions

| Situation | Behavior |
|---|---|
| matplotlib not installed | Return `{"error": "matplotlib_missing", "hint": "pip install matplotlib"}` |
| DB file missing or not readable | Return `{"error": "database_unavailable", "path": "./data/jobhunter.db"}` |
| `user_id` not found in `users` | Return `{"error": "unknown_user", "user_id": <id>}` |
| Output path not writable | Return `{"error": "output_not_writable", "output": "<path>"}` |
| Zero stage counts | Valid result: render the chart with zeros and note the empty pipeline |

---

## Non-goals

- **Never modify the database** — the `mode=ro` URI makes writes impossible by construction.
- **No email sending** — the chart shows sent counts; it never triggers sends.
- **No browser automation** — no scraping, no Playwright, no web fetching.
- No dashboard server or live-updating app — one static chart per invocation.

---

## Guardrail — user scoping

Every stage query filters `job_analyses.user_id` / `email_drafts.user_id`. The `jobs` table is a global pool with no `user_id` column, so raw `jobs` counts must never be charted as per-user funnel stages.

---

## Limitations

- The funnel reflects database activity only; replies and interviews (not stored in the schema) are excluded.
- "Analyzed" is the per-user upper bound (user analyses); total scraped listings live in the global `jobs` table and are not per-user.
- Matplotlib is the only supported backend; no fallback to seaborn/plotly is provided.