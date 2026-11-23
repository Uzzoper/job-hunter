# Provider Config Reference

All configs live in `src/main/resources/application.yaml` (overridden by env vars / docker-compose.override.yaml).

**Important**: inside the backend Docker container, `application.yaml` is NOT a standalone file — it is packed inside `/app/app.jar` at `BOOT-INF/classes/application.yaml`. To read it from the host:

```bash
docker cp job-hunter-backend:/app/app.jar /tmp/app.jar
unzip -p /tmp/app.jar BOOT-INF/classes/application.yaml | grep -A5 "linkedin:"
```

## Gupy

```yaml
scraper:
  gupy:
    base-url: https://employability-portal.gupy.io
    keywords: desenvolvedor,programador,estágio,junior,ti,tecnologia,dev,software,analista
    limit: 100
    timeout-seconds: 10
```

- **Status:** ✅ Primary source, 1.690+ jobs, reliable
- **Notes:** Covers most Brazilian market

## InfoJobs

```yaml
scraper:
  infojobs:
    base-url: https://www.infojobs.com.br
    keywords: desenvolvedor,programador,estágio,junior,ti,tecnologia,dev,software,analista
    max-pages: 3
    timeout-seconds: 10
    detail-concurrency: 2
    detail-timeout-seconds: 5
    max-detail-fetch: 20
    # locations filter (in normalizer, not here):
    # Remoto,Home office,Todo Brasil,Ponta Grossa,Paraná,PR,Curitiba
    # exclude-keywords: senior,sênior,sr,sr.,pleno,pl.,lead,especialista,bdr
```

- **Status:** ⚠️ Works but 96% of cards are lost
- **Detail fetch:** ✅ Works (JSON-LD, not CSS selectors)
- **Real bottleneck:** `locations` filter only accepts PR + remote
- **`max-detail-fetch: 20`:** Only 20 of ~524 cards get detail attempts; the rest are `detailSkipped`

## LinkedIn

```yaml
scraper:
  linkedin:
    enabled: true
    mode: service          # "service" (Playwright) | "jsoup" (fallback)
    service-url: http://linkedin-scraper:3000
    base-url: https://www.linkedin.com
    keywords: "desenvolvedor,programador,estágio,junior,ti,tecnologia,dev,software,analista"
    locations: "Brazil,São Paulo,Rio de Janeiro,Curitiba,Remote"
    # Single LinkedIn geo ID (Brazil). Verified live: repeated geoIds return an
    # empty search page, and the other IDs were not Brazil (Peru/UK/invalid).
    geo-ids: "106057199"
    seniority: "entry_level"
    work-type: "remote,hybrid,on-site"
    time-range: "past_month"
    max-jobs: 150
    timeout-seconds: 30
    detail-fetch-delay-millis: 500
```

- **Status:** ✅ Works correctly — returns mostly Brazilian jobs when `geo-ids` is properly configured
- **geoId passthrough:** LinkedIn returns an empty page when repeated `geoId` parameters are sent. The backend forwards only the first geoId from config (commit `df54652`).
- **geoId values:** Only `106057199` is Brazil. Other IDs returned Peru (`102927786`), UK (`105972731`), or empty (`105906364`).
- **`max-jobs: 60`:** Raised from 25 after the geoId filter proved effective (commit `4c055e1`).
- **Email rate:** ~4.5% (3/67) — emails come from detail page metadata, NOT description text.

### LinkedIn geoId validation procedure (issue #62 fix verification)

To verify that the geoId filter is actually working (not just eyeballing the first page):

```bash
# Fetch from the scraper directly (bypasses backend dedup/filter)
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
print(f"Brazilian: {br_count}/{len(jobs)} ({br_count/len(jobs)*100:.0f}%)")
print(f"Foreign: {len(jobs) - br_count}")
EOF
```

Expected result after fix: >90% Brazilian (typically ~98-100%).

---

## LinkedIn Microservice

- **Health:** `curl http://localhost:3000/health` → `{"data":{"status":"ok","browser":"connected"}}`
- **Search:** `curl http://localhost:3000/api/jobs?keywords=java+junior&geoId=106057199` → returns up to 60 jobs
- **Mode:** `service` (Playwright automation) or `jsoup` (fallback)
- **Browser warmup:** browser is `disconnected` until the first real scrape request triggers Playwright launch. Force warmup: `curl "http://localhost:3000/api/jobs?keywords=test&geoId=106057199"`
- **geoId in scraper code:** the Node.js route (`routes/jobs.ts`) parses repeated `geoId` query params as an array; the scraper (`scrapers/search.ts`) appends each as `params.append("geoId", ...)`. Backend sends only one.
