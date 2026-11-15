# Session 2026-09-19: Triage Rules & Workflow Corrections

## New Triage Rules (user corrections)

These rules apply from the start of every new cycle — no exceptions:

1. **Gupy apenas** — LinkedIn is OUT of scope for automation. Filter `url NOT LIKE '%linkedin%'` before any navigation.
2. **lifecycle_state IS NULL** — never re-process a job that already has a lifecycle state.
3. **Exclude processed + MANUAL queue** — check both `applications/*.json` and `attempts/*.json` before navigating.
4. **postedAt mais recente primeiro** — sort by posted date descending.
5. **DADOS_PESSOAIS → MANUAL** — if form asks for RG, órgão emissor, nome da mãe/pai, naturalidade: record attempt with `outcome: "MANUAL"` and move on. Do NOT ask the human.
6. **Eliminatória → pula com motivo** — if the job description requires something the user doesn't have (declared values in bot memory: superior completo, years of experience, specific tech stack), skip and record the reason.

## Workflow: Batch-First Triage

**Problem found this session**: Navigating to each company's Gupy page to find the job listing is extremely inefficient. Many jobs are stale (404), not listed on the company page, or are presencial.

**New workflow**:

1. **Fetch** → `POST /api/jobs/fetch/gupy`
2. **Filter via API** → `GET /api/jobs?minScore=<threshold>` then filter:
   - `lifecycleState IS NULL`
   - `url` contains `gupy.io` (not linkedin)
   - ID NOT IN (already_processed ∪ attempts)
3. **Report** → tell the human how many new jobs, how many are score ≥ threshold, breakdown
4. **Analyze** → fetch `/api/jobs/{id}` for each candidate to check:
   - Remote check (description/title mentions remoto/home office)
   - Eliminatory requirements (superior completo, years, stack)
   - Personal data requirements (RG, filiação)
5. **Queue** → only proceed to apply for jobs that pass all filters
6. **Apply** → one by one via direct URL navigation (`Page.navigate` to `/jobs/{id}/apply`), partials every 5

## Direct URL Navigation (not list clicks)

**Problem**: `browser_click` on Gupy SPA listing pages often doesn't navigate to the job detail — the SPA intercepts clicks or the page target frame is wrong.

**Solution**: Always navigate DIRECTLY to the job URL via CDP `Page.navigate`:
- Listing page → `Runtime.evaluate` to find the link href
- Then `Page.navigate` to that URL directly
- Or use the URL from the API: `https://<company>.gupy.io/job/<id>?jobBoardSource=gupy_portal`

## No High-Score Jobs Found

If no jobs meet the threshold after filtering:
- Report clearly: "Nenhuma vaga nova com score ≥ X"
- Show score distribution of new jobs
- Suggest lowering threshold or waiting for next scrape
- **Do NOT waste time navigating to pages that won't produce applications**

## Session Summary Format

When the human asks "mais alguma para aplicar?" or similar:
1. First check lifecycle on any in-progress jobs
2. Fetch new batch from API
3. Filter with all rules
4. Report counts (total new, by score bucket, remote vs presencial)
5. Only then proceed or stop

## Manual Queue Delivery

At session end or when requested:
- Read `attempts/*.json` files
- Present as table: ID | Company | Score | Barrier | URL
- Confirm all records (applications + attempts) are saved
