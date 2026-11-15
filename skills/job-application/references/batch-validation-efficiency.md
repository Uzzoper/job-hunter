# Batch Validation Efficiency

Patterns for efficiently processing large batches of job listings.

---

## 1. Pre-validate via API before browsing

Before navigating to each company page, check if the job URL resolves to a real Gupy page:

```python
# Batch-check which jobs have valid Gupy URLs
for job in jobs:
    url = job.get('url', '')
    if 'linkedin' in url:
        continue  # Skip LinkedIn
    # Try to fetch job detail via API to confirm it exists
    req = urllib.request.Request(f'http://localhost:8080/api/jobs/{job["id"]}', headers={'X-Bot-Token': token})
    try:
        with urllib.request.urlopen(req) as r:
            detail = json.loads(r.read())
            if '.gupy.io' in detail.get('url', ''):
                valid_jobs.append(job)
    except:
        pass  # Skip invalid
```

This avoids wasting browser sessions on 404 pages.

---

## 2. Search by keyword at the company page

Once on a company's Gupy page, use the search box to filter for relevant roles:

1. Navigate to `https://<company>.gupy.io/`
2. Type keyword into search box (ref from snapshot)
3. Press Enter
4. Check if the desired role appears in filtered results
5. If not, the job is NOT_AVAILABLE → skip immediately

Common keywords: `desenvolvedor`, `full stack`, `front`, `back`, `software`, `fullstack`

---

## 3. Remote-only filter

Juan's preference: **SÓ REMOTO**. When a job listing shows:
- `São Paulo - SP` (onsite) → NOT_AVAILABLE
- `Híbrido` → NOT_AVAILABLE  
- `Trabalho Remoto` → proceed

Skip these immediately without clicking.

---

## 4. Known barriers that stop the flow

| Barrier | Action |
|---|---|
| `/candidates/auth` or `/login` redirect | Session expired. Stop, ask user to login, resume. |
| 404 page | Vaga expirou/removida. NOT_AVAILABLE, skip. |
| "Applications closed" / "No longer accepting applications" | Posting dead though the URL resolves (stale DB snapshot). NOT_AVAILABLE, skip. |
| Job not in listing | Vaga expirou/removida. NOT_AVAILABLE, skip. |
| LinkedIn URL | Out of scope. Skip. |
| RG/filiação/naturalidade required | MANUAL outcome. Record and skip. |
| Superior completo + X anos (eliminatório) | ELIGIBILITY_BLOCK. Skip. |

---

## 5. Screening answer defaults

Juan's declared profile values:
- **Angular:** Sim (2 projetos próprios, aprende rápido)
- **Superior completo:** Não (cursando, previsão 2027)
- **Pretensão salarial:** Aberto a negociação, conforme mercado júnior
- **Inglês:** C1 intermediário

Only use declared values. Never invent.
