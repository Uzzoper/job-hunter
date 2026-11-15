# Session 2026-09-21 — Quarantine, Divergence, and Triage Lessons

## Summary

Session where a full triage + apply batch on Gupy remote jobs revealed several bugs and workflow gaps. Key findings:

1. **Branch confusion** — User corrected: work should be on `main`, not `dev`. The repo uses `main` as primary.
2. **Score 0 ≠ eliminated** — `matchScore = 0` means analysis was NEVER RUN, not that the job is a poor match. Fresh Gupy listings have score 0 until analyzed.
3. **URL truncation** — Gupy URLs from the API are truncated (`eyJqb2...sIn0=` with `...` literal). Need to use numeric `id` from `GET /api/jobs/{id}` to get full URL.
4. **Record divergence** — `applications/<id>.json` may say `applied` while `lifecycleState` in backend is still `null`. Backend is source of truth.
5. **Quarantine workflow** — Orphaned records (applied in file but not in backend, no screenshot) should be moved to `quarantine/` dir, not deleted.

## Detailed Findings

### Bug: URL Truncation in GupyProvider

URLs stored in DB are literally truncated:
```
id=<n> | https://<company>.gupy.io/job/eyJqb2...sIn0=?jobBoardSource=gupy_portal
id=<n> | https://<company>.gupy.io/job/eyJqb2...sIn0=?jobBoardSource=gupy_portal
```

The `...` is literally in the database string, not a display artifact. This prevents navigation.

### Bug: Record Divergence

Job 609 had:
- `applications/609.json`: `status: "applied"`, `applied_at: 2026-09-20`
- `attempts/<n>.json`: `outcome: SESSION_EXPIRED`
- `screenshots/609.png`: DID NOT EXIST
- `GET /api/jobs/609`: `lifecycleState: null`

The `verdict.py` wrote the local record without:
1. Persisting screenshot
2. Updating backend lifecycleState

Backend is the **source of truth**.

### Workflow Correction: Two-Pass Triage

**Pass 1**: Filter remote jobs → run analyze on ALL score-0 jobs (parallel, 2-3 workers)
**Pass 2**: After analysis completes → filter by score ≥ 70 → check eliminatórias

User explicitly corrected: do NOT eliminate score-0 jobs without analysis.

### Triage Results (this session)

- 2,689 lifecycle NULL jobs
- 130 remote new (score 0 = not analyzed)
- 110 analyzed in background with 3 workers
- 14 elegible (≥70), 6 already SUBMITTED, 8 restantes
- After eliminatória check: 1 truly elegible (`<id>`)
- `<id>` had orphaned applied record → quarantined, needs re-apply

### Scripts Used

- `/tmp/analyze_remote_zero.py` — parallel analysis with ThreadPoolExecutor(3)
- `/tmp/check_elegible.py` — verify lifecycle + eliminatórias
- `/tmp/quarantine_609.py` — move orphaned records to quarantine
