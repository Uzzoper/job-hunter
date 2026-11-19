---
name: listing-hunt
description: "Daily listing hunt: fetch, filter, analyze, digest."
version: 1.0.0
author: jobhunter-bot
category: jobhunter
metadata:
  hermes:
    tags: [job-hunt, batch-analysis, listing-discovery]
    related_skills: [job-portal-browser, analyzer, report-generator]
---

## When to Use

- User asks to "achar vagas", "daily listing hunt", or "find jobs" with volume rules.
- User wants to fetch ALL jobs from the API and run AI analysis on survivors.
- User asks for a ranked digest with APPLY/MAYBE classification.
- Triggered by "Daily listing hunt #N" or similar phrasing.

# Skill: Listing Hunt

> Batch discovery + analysis workflow for the `jobhunter-bot` profile.
> Complements `job-portal-browser` (API-first listing) with the aggressive
> "analyze everything" variant.

## Procedure

**Scratch dir** — `SCRATCH=<scratch-dir>`.
Every intermediate file (`all_jobs.json`, `analysis_results.json`, per-batch
snapshots) is written there, never `/tmp`.

### 1. Fetch all jobs

```bash
TOKEN=$(grep BOT_SERVICE_API_KEY <job-hunter-repo>/.env | cut -d= -f2)
SCRATCH=<scratch-dir>
curl -s "http://localhost:8080/api/jobs?excludeApplied=true" \
  -H "X-Bot-Token: $TOKEN" -o "$SCRATCH/all_jobs.json"
```

No minScore, no hasEmail filter — read broadly.

### 2. Pre-filter (dev + junior + remote)

Filter in Python:
- **Dev role**: title contains dev, desenvolvedor, desenvolvimento,
  developer, software, backend, frontend, full-stack, fullstack, java,
  python, javascript, typescript, node, react, engenheiro, programador,
  analista de sistemas, qa, devops, mobile, .net. (`dev` alone does NOT
  match "Desenvolvedor" — the substring is `des`+`env`, not `dev`.)
- **Junior**: exclude titles with pleno, sênior, senior, mid-level, mid
  level, staff, lead, coordenador, gerente, manager, head, director,
  architect, arquiteto, plus the abbreviations `PL` (pleno) and roman
  numerals `II`/`III`/`IV` (job levels). Match short tokens on word
  boundaries — `principal` also occurs inside `principalmente` and would
  wrongly exclude a genuine júnior role.
- **The description outranks the title.** A posting whose title says
  "Junior"/"Estágio" but whose description requires "Pleno", "Sênior" or
  "X+ anos de experiência" is NOT a junior role — exclude it. Titles are
  written by recruiters and routinely overstate seniority downward; the
  description carries the real requirement. Apply the same rule when
  building a batch: a junior-titled row with a pleno description is dead
  weight, not a candidate.
- **Remote/Brazil**: keep only jobs whose title/description contains the
  `remot` stem (matches remoto/remota/remote) or "home office"; then exclude
  jobs mentioning non-Brazil locations (US, UK, Germany, France, Canada,
  Australia, etc.). A title like "(SP ou DF)" signals a location-bound role
  even when the benefits text mentions home office.
- **Industrial "programador" is not software dev**: exclude titles with
  CNC, CLP, dobra/deira, manutenção, tráfego, PCP, usinagem,
  eletromecânica — "Programador CNC/CLP/Dobradeira" is a machine operator.

### 3. Run AI analysis on survivors without scores

Batch script (3 concurrent, 60s timeout):

```python
import json, time, requests, concurrent.futures, os

TOKEN = open('.env').read().split('BOT_SERVICE_API_KEY=')[1].split('\n')[0].strip()
BASE = "http://localhost:8080/api/jobs"
SCRATCH = os.path.expanduser('<scratch-dir>')

jobs = json.load(open(f'{SCRATCH}/all_jobs.json'))
results_data = json.load(open(f'{SCRATCH}/analysis_results.json')) if os.path.exists(f'{SCRATCH}/analysis_results.json') else {'results': {}, 'errors': []}
already = set(str(k) for k in results_data['results'].keys()) | set(str(e[0]) for e in results_data['errors'])

# ... pre-filter logic ...
need = [j for j in survivors if str(j['id']) not in already and j.get('matchScore') is None]
batch = need[:20]  # Always first N of remaining

def analyze(job):
    try:
        r = requests.post(f"{BASE}/{job['id']}/analyze", headers={'X-Bot-Token': TOKEN}, timeout=60)
        if r.status_code == 200:
            return job['id'], r.json().get('matchScore'), None
        else:
            return job['id'], None, f"HTTP {r.status_code}"
    except Exception as e:
        return job['id'], None, str(e)[:80]

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    futures = {executor.submit(analyze, j): j for j in batch}
    for future in concurrent.futures.as_completed(futures):
        job_id, score, err = future.result()
        if err:
            results_data['errors'].append((job_id, err))
        else:
            results_data['results'][job_id] = score
        # write after EVERY result, not once at the end
        json.dump(results_data, open(f'{SCRATCH}/analysis_results.json', 'w'))
```

For more than ~20 unscored survivors, run this script with
`background=true, notify=true` — a single analysis takes 20-45s, so a
foreground call will time out and a script that only writes at the end
loses the whole batch.

### 4. Merge scores at the end

```python
jobs = json.load(open(f'{SCRATCH}/all_jobs.json'))
results = json.load(open(f'{SCRATCH}/analysis_results.json'))['results']
for job in jobs:
    if str(job['id']) in results:
        job['matchScore'] = results[str(job['id'])]
```

### 5. Present top 20 digest

Table: `# | id | title | company | score | fonte | email? | link`
(`fonte` = the `source`/`provider` field: gupy, infojobs, linkedin,
linkedin-post, github, greenhouse.) Then APPLY (≥60) / MAYBE (50-59)
counts, and split the APPLY set into **portal-eligible** (gupy/infojobs) vs
**manual** (linkedin, linkedin-post, github, greenhouse).
Counts: seen / filtered / analyzed / saved / dupes.

### 6. Memory note

One compact line in `§ jobs-hunt` (drop per-post details of closed passes;
keep the whole memory inside its budget):
```
jobs-hunt(DD/MM): Nseen/Nfilt/Nanalyzed/NAPPLY(≥60)/NMAYBE(50-59)/Nemail. Checkpoint: <highest activity id>.
```

### 7. Batch portal apply-flow (when the user approves applying)

Standing rules for turning the digest into portal (Gupy/InfoJobs) applications:

**Openness check first** — the API list is a stale snapshot. Before proposing
or submitting any portal batch, load each job page and confirm it is still
accepting applications. A closed posting renders "Candidaturas encerradas" /
"Inscrições encerradas" / "Vaga encerrada" and cannot be submitted: drop it,
report it, and never mark it applied. A batch approved straight from the digest
can be entirely dead, so verify openness item by item, not per batch.

Run it as one read-only browser pass over the batch, one page at a time:

```js
// per job page — closed iff the body text matches, open iff it shows a deadline
t = document.body.innerText.toLowerCase();
fechado  = /candidaturas encerradas|inscri\u00e7\u00f5es encerradas|vaga encerrada|processo seletivo desta vaga foi encerrado|n\u00e3o recebe mais candidaturas|ops! esta vaga n\u00e3o est\u00e1 mais dispon\u00edvel/.test(t);
aberto   = /inscri\u00e7\u00f5es abertas at\u00e9/.test(t);
```

Report `id | fechado | aberto` per item; a job with neither signal gets a
manual read before it enters a batch. InfoJobs is the common case: a dead
posting renders the site's normal chrome with only the `Ops! Esta vaga não
está mais disponível.` sentence as the closed signal, so that phrase must be
in the regex or those rows report as neither signal and force a manual read.

**InfoJobs requires a logged-in session.** Clicking `CANDIDATAR-ME` on an
unauthenticated session redirects to `login.infojobs.com.br/Account/Login`
and no application can proceed — check the session once for the whole batch
rather than discovering it per job. Never fill the login form: hand the human
the login URL and continue after they confirm. The login must be performed in
the **bot's own Chromium** (the CDP profile the browser tool drives) — portal
sessions are per-profile, so a login done in the user's daily browser does NOT
carry over and the bot still sees the login page. After the user says they are
logged in, verify it from the bot's browser (load the portal homepage, confirm
the account name renders and the Login control is gone) before re-running the
batch; a claim of "logged in" is not evidence.

- **Match the regex on the FULL body text; slice only for display.** Truncating
  the text before the test silently drops the signal words — a closed page whose
  "Candidaturas encerradas" sits past the cut reports as open, and a page whose
  "Candidatar-se" button renders early reports as open for everything. Run the
  match on the complete `document.body.innerText`, then slice the string you
  print. A single batch pass can mislabel a dozen jobs this way.

1. **Score floor** — only jobs with `matchScore >= 60`; prioritize the
   júnior/remote subset first.
2. **No volume cap** — the batch is EVERY eligible open job, not a capped
   subset. There is no per-day or per-run apply limit; the score floor and the
   two dedupes below are the only filters. Enumerate the whole eligible set
   (score >= 60, not applied, new company, deduped by `jobId`) and let the
   openness pass drop the dead ones. Eligibility is **júnior/estágio only** —
   a junior-titled posting whose description asks for pleno/sênior is out,
   even when its score is high.
3. **Batch review gate** — print the batch (`id | title | company | score |
   link`) BEFORE any submission and wait for one explicit approval of that
   batch. No standing auto-apply: every batch needs its own approval.
4. **Dedupe guard** — never apply twice to the same job or the same company.
   Build the initial applied-state from BOTH channels and record it in the
   `§ jobs-hunt` note:
   ```sql
   SELECT d.job_id, j.company
   FROM email_drafts d LEFT JOIN jobs j ON j.id = d.job_id
   WHERE d.status = 'SENT';
   ```
   plus every `memails/applications/*.json` — portal applies never appear in
   `email_drafts`, so a drafts-only applied-state re-proposes them. Normalize
   company names (trim + lowercase). A company already applied excludes all
   its still-open jobs from the batch.

   **Gupy stores one vacancy under several API ids.** The same posting is
   saved once per `?jobBoardSource=` value (`gupy_portal`, `mcp_candidate`, …)
   with a *different* `id` but an identical decoded slug, so an id-based
   "new since the last hunt" diff proposes it again. Dedupe by the decoded
   `jobId`, never by row id:
   ```python
   import base64, re, json
   slug = re.search(r'/job/([^?]+)', url).group(1)
   meta  = json.loads(base64.urlsafe_b64decode(slug + '=' * (-len(slug) % 4)))
   # meta["jobId"] is the true vacancy identity
   ```
   Rows sharing a `jobId` are ONE vacancy: treat every one of them as already
   applied. This also protects `job-application`'s per-id idempotency record,
   which would otherwise let the duplicate through.
5. **Only Gupy/InfoJobs are automated** — jobs sourced from LinkedIn (jobs
   page or feed posts), GitHub or Greenhouse are applied manually by the
   human. Offer a ready-made list (direct links + prefilled data) or steer
   the batch to Gupy/InfoJobs; never attempt LinkedIn Easy Apply.

Submission itself runs the `job-application` skill (`apply.py` + MCP
executor loop). Email channel: when the job has a contact e-mail and no
portal apply, use `job-application-email` (draft → HOLD → send).

## Pitfalls

- **Shrinking list index bug**: when the `need` list shrinks as jobs get
  scored, using `batch_num * batch_size` as an offset skips jobs. Always
  take `need[:batch_size]` (first N of remaining).
- **Per-batch file overwrite**: writing `all_jobs_updated.json` each batch
  loses previous scores. Merge from `analysis_results.json` at the end.
- **Timeout discards the batch**: the analysis script must persist after
  every result. A foreground call that times out kills the process, and a
  script whose only `json.dump` sits after the executor block leaves
  `analysis_results.json` empty — all completed work is lost. Long runs
  belong in a background process.
- **400 = empty description**: the API returns 400 "Job description must
  not be empty" for jobs with no description. Skip these — cannot analyze.
- **502/timeout**: retry once with 2 concurrent workers.
- **Concurrency**: 3 workers max. Higher causes 502s from the backend.
- **Analysis is non-deterministic** — re-running
  `POST /api/jobs/{id}/analyze` on the same job can return a different
  `matchScore` (the AI is sampled, not exact). Treat the score as a range,
  not a fixed value; re-check a borderline job before promising it in a
  batch instead of assuming the first number is authoritative.
- **Fetch vs list**: `GET /api/jobs` only reads stored rows; to pull fresh
  listings use `POST /api/jobs/fetch/{gupy,infojobs,linkedin,lever}` (or
  `/fetch` for all). A fetch that exceeds the HTTP timeout still runs
  server-side — re-poll `GET /api/jobs` to see the rows it saved.
- **LinkedIn's fetch is asynchronous**: `POST /api/jobs/fetch/linkedin` answers
  `202 {"status":"started"}` immediately — poll
  `GET /api/jobs/fetch/linkedin/status` until `state` is `completed`/`failed`
  and read `fetched`/`saved`/`withEmail`/`keywords` from that payload. Budget
  ~15 min wall-clock; gupy/infojobs/lever return synchronously.
- **`github`/`greenhouse` are sources, not providers**: rows carry those
  `source` values, but there is no `/fetch/<fonte>` route for them — a
  "check all sources" pass covers only gupy, infojobs, lever and linkedin.
- **Stale snapshot ≠ open posting**: `matchScore` and the digest describe the
  stored row, not the live vacancy. Jobs posted weeks earlier are frequently
  already closed, and the portal page — not the score — decides whether a
  submission is possible. Re-check openness before building a batch.
- **Same Gupy vacancy, different API id**: one posting can be stored under two
  `id`s that differ only in the `?jobBoardSource=` query param; the URL slugs
  decode to the same `jobId`. Idempotency keyed on the API `id` — or a
  "new since last hunt" id diff — lets a duplicate application through.
  Decode the slug and compare `jobId`.
- **Scratch belongs in the profile dir, not `/tmp`**: a host reboot wipes
  `/tmp` and kills background jobs, losing `all_jobs.json` /
  `analysis_results.json`. Scores already POSTed to `/analyze` persist
  server-side, so a fresh `GET /api/jobs` recovers them after a killed run.
- **Provider speed**: LinkedIn (Playwright, 127.0.0.1:3000) is the
  bottleneck — 30s read timeouts per keyword, minutes for 30 jobs.
  `enrich-emails` also exceeds 120s. Gupy/InfoJobs/Lever are fast.
- **PCD / affirmative vacancies**: titles carrying "Vaga Afirmativa",
  "PCD", "Exclusiva PCD" or diversity quotas are reserved seats — flag them
  in the digest/apply batch instead of silently including them.
- **LinkedIn Jobs page vs feed**: the LinkedIn feed (posts) is often dry;
  the LinkedIn Jobs search (`/jobs/search/?keywords=<term>&location=Brasil&f_WT=2`,
  paginate with `&start=25`) exposes far more listings. Applications there are
  manual-only (see the apply-flow rules).
- **Memory capacity**: the memory store has a hard char budget and rejects an
  over-limit write (the whole batch rolls back). Keep it to the target profile,
  dated limit amendments, the checkpoint, and ONE history line with pass
  outcome counts only; drop per-post details of closed passes. When full,
  consolidate or shorten stale lines in the SAME batch as the add — never skip
  the save.
- **Profile search keywords** — junior compounds first, no broad terms:
  `desenvolvedor junior`, `dev junior`, `programador junior`,
  `estagio desenvolvedor`. Broad terms (`dev`, `desenvolvedor`) pull in
  pleno/sênior noise. Update via `PUT /api/profile` → `preferences.keywords`,
  preserving the rest of the profile — send the whole current profile back
  with only `preferences.keywords` changed (resumeText/skills/tone/projects
  intact).
