# API-First Triage Workflow & SPA Click Failures (2026-09-19)

## Problem

Repeated violations of the "triage via API before navigating" rule caused wasted sessions:
- Navigating to company listing pages to "find" jobs that were already in the DB
- Clicking through Gupy SPA pages when direct URL navigation was available
- Applying to jobs that were already applied, 404, presencial, or LinkedIn

## Root Cause

The `browser_click` tool frequently fails on Gupy's React SPA — the click registers as successful but the page does not advance. This led to:
- Multiple wasted attempts per job
- Navigation to listing pages as a "workaround"
- Time lost on stale (404) or ineligible jobs

## Correct Workflow (MANDATORY)

### Step 1: API Verification (NEVER skip)
```python
# Check lifecycle, URL, duplicates BEFORE any browser work
GET /api/jobs/{id}
# Verify: lifecycleState IS NULL
# Verify: url contains "gupy.io" (NOT linkedin)
# Verify: id NOT IN (applications + attempts)
# Check for duplicates: same company + same /jobs/<id> slug
```

### Step 2: Eligibility Check via API
```python
# Check description for:
# - Remote indicators: "Remoto", "Home Office", "Trabalho Remoto", "HOME OFFICE"
# - Eliminatory: "Superior completo" (check declared education status in bot memory), 5+ years experience, stack mismatch
# - DADOS_PESSOAIS: RG, órgão emissor, nome da mãe/pai, naturalidade
```

### Step 3: Direct URL Navigation (NEVER browse listings)
```python
# Navigate DIRECTLY to the apply page:
CDP: Page.navigate(url=f"{job_url}/apply?jobBoardSource=gupy_public_page")
# OR navigate to job page first, then click Candidatar-se via Runtime.evaluate
```

### Step 4: SPA Click Pattern
```javascript
// If native browser_click fails twice, use CDP Runtime.evaluate:
(function(){
  const b = Array.from(document.querySelectorAll('button'))
    .find(x => x.textContent.includes('Candidatar-se') && x.getBoundingClientRect().width > 0);
  if(b){b.scrollIntoView({block:'center'}); b.click(); return 'clicked'}
  else {return 'not found'}
})()
```

## Session Corrections (2026-09-19)

1. **"Pq esta ineficiente?"** — Too many NOT_AVAILABLE jobs in batch; switched to API-first triage
2. **"continua aplicando"** — User had to repeatedly tell me to stop navigating and start filtering
3. **"pula pra proxima"** — When a job is blocked (RG/filiação), mark MANUAL and move on immediately
4. **"caso pedir rg pode pular depois me inscrevo manualmente nestas"** — DADOS_PESSOAIS rule
5. **"ajuste na regra DADOS_PESSOAIS: ao pular, grava attempt com outcome MANUAL"** — Formalized MANUAL attempt pattern
6. **"pq esta ineficiente?" (second time)** — Muito dado stale no banco, LinkedIn misturado, gateway expira, cada aplicação exige ~10 interações
7. **"continua só nos válidos. Triagem via API antes de navegar"** — Explicit API-first mandate
8. **"Aplica nos 7 marcados... verifica antes de aplicar nas duas"** — Duplicate detection requirement
9. **"Não aplica os 60-69 ainda. Me apresenta os 25 em tabela"** — Calibration sample workflow
10. **"Monta a amostra da calibragem (#78)"** — Calibration sample format

## Duplicate Detection

Before applying, check if another job has the same URL base:
- Same company + same `/jobs/<id>` slug = duplicate
- Apply only once, mark the other as `NOT_AVAILABLE` with barrier "duplicata de <id>"

## MANUAL Attempt Format

```json
{
  "job_id": "<numeric-id>",
  "job_company": "<company>",
  "job_title": "Engenheiro de Software Pl. (Front-end)",
  "outcome": "MANUAL",
  "barrier": "DADOS_PESSOAIS (RG, órgão/estado emissor, nome da mãe, nome do pai, naturalidade)",
  "url": "https://<company>.gupy.io/jobs/<gupy-job-id>",
  "match_score": 67,
  "recorded_at": "2026-09-18T20:15:00-03:00"
}
```

## Eliminatory Requirements (from memory)

- **Superior completo**: if required and not declared complete in bot memory → eliminatory
- **5+ years experience**: check declared experience in bot memory → eliminatory if unmet
- **Stack mismatch**: if the job requires a stack not declared in bot memory → eliminatory
- **DADOS_PESSOAIS-style barriers** (RG, naturalidade, parent names): treated per session policy

## Remote-Only Filter

The user is remote-only (declared in bot memory — check current city and
relocation preferences there). Discard:
- Presencial (any city)
- Híbrido in cities the user has NOT explicitly approved — only hybrid cities
  listed as approved in bot memory pass, and even then confirm in chat

## Calibration Sample Format (#78)

For score calibration, present ~50 jobs in table format:
- 15 from ≥70 (with real status: applied, rejected, NOT_AVAILABLE, etc.)
- 20 from 60-69
- 15 from <60
- Columns: id, empresa, título, score, postedAt
- Include 2 lines of description for context
- User marks which ones to apply
- This becomes the calibration sample for score threshold tuning
