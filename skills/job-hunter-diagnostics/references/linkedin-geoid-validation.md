# LinkedIn geoId Validation Procedure

Used to verify that the LinkedIn geoId filter is actually working end-to-end (not just eyeballing the first page).

## When to use

- After changing `geo-ids` config in `application.yaml`
- After redeploying the backend or scraper
- When a GitHub issue references LinkedIn returning non-Brazilian jobs
- Before closing an issue about LinkedIn location filtering

## Procedure

### 1. Check deployed config (not source code)

The live config is inside the backend JAR, not the source tree:

```bash
docker cp job-hunter-backend:/app/app.jar /tmp/app.jar
unzip -p /tmp/app.jar BOOT-INF/classes/application.yaml | grep -A5 "geo-ids\|max-jobs:"
```

Expected after fix: `geo-ids: "106057199"`, `max-jobs: 60`.

### 2. Check scraper code (verify passthrough)

```bash
docker exec job-hunter-linkedin-scraper cat /app/dist/scrapers/search.js | grep -A5 "geoId\|buildSearchUrl"
docker exec job-hunter-linkedin-scraper cat /app/dist/routes/jobs.js | grep -A10 "geoId"
```

Expected:
- `search.js`: `params.append("geoId", trimmed)` inside a loop
- `routes/jobs.ts`: `const geoIds = queryStringList(geoId)` → passes array to scraper

### 3. Verify backend → scraper forwarding

```bash
# Backend sends first geoId only (avoids LinkedIn empty-page bug)
unzip -p /tmp/app.jar BOOT-INF/classes/com/{{HANDLE}}/job_hunter/infrastructure/scraper/client/LinkedInScraperClient.class | strings | grep -i "geoId"
```

Look for `findFirst()` in the decompiled bytecode or read the source at:
`src/main/java/com/{{HANDLE}}/job_hunter/infrastructure/scraper/client/LinkedInScraperClient.java` (line ~65-71).

### 4. Fetch and analyze locations

```bash
# Fetch from scraper directly (bypasses backend dedup/filter)
curl -s "http://localhost:3000/api/jobs?keywords=java+junior&location=Brazil&geoId=106057199" > /tmp/linkedin_results.json

# Analyze locations with Python
python3 << 'EOF'
import json
with open('/tmp/linkedin_results.json') as f:
    data = json.load(f)
jobs = data.get('data', [])
states = ['AL','BA','CE','DF','ES','GO','MA','MG','MS','MT','PA','PB','PE','PI','PR','RJ','RN','RO','RR','RS','SC','SE','SP','TO']
br_count = sum(1 for j in jobs if any(s in j.get('location','') for s in states)
                   or 'brazil' in j.get('location','').lower())
foreign = [j.get('location','') for j in jobs if not (any(s in j.get('location','') for s in states)
             or 'brazil' in j.get('location','').lower())]
print(f"Brazilian: {br_count}/{len(jobs)} ({br_count/len(jobs)*100:.0f}%)")
print(f"Foreign locations: {set(foreign)}")
EOF
```

### 5. Evaluate against criteria

| Criterion | Expected |
|---|---|
| Brazilian majority | >50% (typically 98-100% after fix) |
| Foreign jobs | 0 or minimal |
| "Remote" alone (no country) | Not counted as Brazilian — flag these |

## False positives to watch for

- **"Recife e Região"** — Brazilian, not foreign (the "e Região" suffix can trip naive regex — no "PE" abbreviation present)
- **"São Paulo, Brasil"** — Brazilian (note the ", Brasil" suffix)
- **"São Paulo, Brazil"** — Brazilian
- **"Brasil"** alone — Brazilian (LinkedIn sometimes returns just the country name)

## Test classification pitfalls

- **State abbreviation detection must use word boundaries**: `\bMG\b` to avoid matching substrings inside other words. Without boundaries, "AM" in "Amazonas" could match.
- **"Brasil" vs "Brazil"**: LinkedIn returns both forms. Check case-insensitively.
- **"Remote" alone does NOT count as Brazilian** — must have city + state abbreviation OR "Brasil"/"Brazil" in the location string.

## `location` field NOT in backend API response

The `JobResponse` DTO does **not** include the `location` field (only `id, title, company, url, description, postedAt, source, contactEmail, draftStatus, matchScore, lifecycleState`). To get location data:

1. Query the scraper directly: `curl "http://localhost:3000/api/jobs?..."` — returns `location` field per job.
2. Or inspect the DB metadata column (JSON) where `locationFilter` is stored as metadata.

## GeoId reference

| geoId | Region | Notes |
|---|---|---|
| `106057199` | Brazil | ✅ Use this one |
| `102927786` | Peru | ❌ Not Brazil |
| `105972731` | UK | ❌ Not Brazil |
| `105906364` | Invalid | ❌ Returns empty |

Sending repeated `geoId` parameters to LinkedIn returns an empty page — backend must send only the first one.

## Known commits

| Commit | Description |
|---|---|
| `17b0690` | feat: forward configured geoIds to LinkedIn scraper |
| `7c7f2f8` | feat: pass geoId through LinkedIn search request |
| `57f6496` | refactor: simplify geoId forwarding |
| `df54652` | fix: forward only first geoId, LinkedIn empties on repeated |
| `4c055e1` | fix: raise linkedin max-jobs after geoId filter works |
