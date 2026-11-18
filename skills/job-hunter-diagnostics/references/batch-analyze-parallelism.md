# Batch Analyze — Parallelism Limits

> Discovered in 2026-09-17 session: analyzing 2043 Gupy jobs crashed the backend.

## The Problem

`POST /api/jobs/{id}/analyze` takes ~40s per call. When batch-analyzing hundreds of jobs:

- **8 workers** → backend crashes after ~25 min (empty response, HTTP 000)
- **428 jobs** completed before crash, **1623 failed**

## Root Cause

Sustained load of ~2 jobs/s for 27 minutes exhausts:
- Backend connection pool (HikariCP + SQLite serialized transactions)
- AI provider rate limits (40s per analysis = Hermes gateway under constant load)
- Spring Boot thread pool for request handling

## Safe Configuration

```python
# Safe batch analyze pattern
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    # 4 workers ≈ 0.12–0.15 jobs/s sustained, no crash expected
```

## Detection & Recovery

**Crash symptoms:**
- API returns empty response (`HTTP 000` / connection refused)
- `docker compose ps` shows backend container "created" but no ports listening
- Backend logs show connection pool exhaustion or OOM

**Recovery:**
```bash
cd <job-hunter-repo>
docker compose up -d backend
# Wait for Spring Boot startup (30-40s)
# Re-run on remaining matchScore: null jobs
```

**Idempotency**: analyze on already-analyzed job returns existing result (no-op), so re-running is safe.

## Gupy Score Distribution (2026-09-17)

From 428 successfully analyzed Gupy jobs:

| Range | Count | % |
|-------|-------|----|
| 0-59 | 387 | 90.4% |
| 60-69 | 16 | 3.7% |
| 70-79 | 23 | 5.4% |
| 80+ | 2 | 0.5% |

- **Score ≥ 70**: 25 jobs (5.8%)
- Mean: 24.4 | Median: 18 | Min: 0 | Max: 88

> 90% of Gupy jobs score below 60 — most are presencial/SP or have zero technical match.
