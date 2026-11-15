# Session 2026-09-20: Scraper Keyword Optimization & Docker Restart

## Problem

The default scraper keywords (`desenvolvedor,programador,estágio,junior,ti,tecnologia,dev,software,analista`) pulled 60 new jobs but:
- **47% were estágio/aprendiz** (score 30-37)
- **0% were dev júnior/pleno** (score ≥ 70)
- Generic terms (`ti`, `tecnologia`, `analista`) pulled QA/infra/support roles

## Solution: Compound Keywords

Replace generic terms with compound, role-specific keywords:

```yaml
scraper.gupy.keywords: >
  desenvolvedor júnior,
  desenvolvedor pleno,
  programador júnior,
  programador pleno,
  backend júnior,
  backend pleno,
  frontend júnior,
  frontend pleno,
  fullstack júnior,
  fullstack pleno,
  desenvolvedor java,
  desenvolvedor python,
  desenvolvedor react,
  desenvolvedor node,
  desenvolvedor typescript,
  desenvolvedor angular,
  desenvolvedor spring,
  engenheiro de software júnior,
  engenheiro de software pleno,
  software engineer junior,
  software engineer mid-level,
  developer junior,
  developer mid-level,
  desenvolvedor,
  programador,
  junior,
  pleno,
  backend,
  frontend
```

**Removed:** `estágio`, `ti`, `tecnologia`, `analista` (pull too much noise)
**Added:** Compound terms with seniority level + stack anchors

## Override Pattern (No Committed Changes)

The backend runs in Docker. To override config without editing committed files:

1. Create `src/main/resources/application-local.yaml`:
   ```yaml
   scraper:
     gupy:
       keywords: "desenvolvedor júnior,desenvolvedor pleno,..."
   ```
2. Restart container: `docker restart job-hunter-backend`
3. Wait for health: `curl http://localhost:8080/actuator/health`
4. Trigger fetch: `POST /api/jobs/fetch/gupy`

The `application-local.yaml` overrides `application.yaml` values automatically via Spring Boot's profile system — no code changes, no commits.

## Remote Detection False Positives

Filtering for "Remoto" in title/description can yield false positives:
- "Exclusiva São Paulo" in title → clearly not remote, but description may mention "remoto" elsewhere
- Keyword "Remato" (typo) in description

**Verification:** After filtering, fetch `/api/jobs/{id}` and manually check the description's actual location line — don't rely solely on keyword search.

## Validation Workflow

After keyword change + fetch:

1. Check total fetched vs saved
2. Report score distribution (≥70, 60-69, 50-59, etc.)
3. Report % estágio/aprendiz (score 1-19)
4. **Rollback if volume drops unexpectedly:** delete `application-local.yaml`, restart Docker container

## Results

With new keywords, fetch of 692 jobs saved only 1 new (rest were duplicates from prior fetch). Score distribution showed 37 jobs ≥70 (dev júnior/pleno target), but 49% of DB still score 1-19 (legacy istagio/aprendiz from old keywords).

The triage filter (lifecycle NULL + score ≥ 70) separates the signal from noise.
