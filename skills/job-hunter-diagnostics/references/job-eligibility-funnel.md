# Measuring job eligibility, not just scraper yield

`totalSaved` measures the pipeline. It does not measure whether the user can
actually take the job. Use this when deciding whether a scraper change helped, and
when advising someone on where to look next.

## Walk the funnel to the last stage

Report every cut, not the top-line:

```
fetched → saved → in target role → target seniority → target work model
```

A representative remote-only junior run:

| Stage | Count |
|---|---|
| LinkedIn jobs in DB | 136 |
| junior/intern in title | 30 |
| …and actually a dev role | 17 |
| …and actually remote | **2** |

The last stage is where the illusion lives.

## Why text classification overcounts remote

Keyword-matching a job description for `remoto|remote` produced false positives:
a page whose work model read **Presencial** was classified remote because the word
appeared in body copy — boilerplate about the company's remote culture, or an
"also we're remote-friendly" line, not the role's own work model.

**Classify work model from the job page's own work-model label, not from keyword
matches in its description.** When the shortlist is small enough to open, open it
and read the label. Bulk text classification is fine for the first two funnel
stages and for triage volume; it is not a basis for telling the user which jobs
to apply to.

## Two sweeps that inflate the count

- A `jr` substring sweep catches non-dev roles — `Montador Mecânico Junior`,
  `Operador(a) de Abastecimento Jr`, `Arrumadeira(o) Jr.`, `MONTADOR Jr DE KIT
  DE ALUMÍNIO`. Require a dev term in the title too.
- A remote-keyword sweep catches on-site roles. See above.

Report the dev-only and page-verified numbers, not the raw grep.

## Low eligible yield is a market fact, not always a bug

Once pagination, keyword iteration, and timeouts are healthy, a funnel ending at a
handful of eligible jobs is usually the size of the market. Junior remote software
roles are structurally rare: companies that hire juniors are small, small companies
do not run distributed eng teams, and the junior remote listings that do exist tend
to want years of experience plus strong English.

**Say this plainly rather than continuing to tune.** When the funnel is this narrow,
config levers are the wrong lever. The productive moves are:

- widen the seniority band to hybrid near the user's city, with explicit approval
- move to a portal where the remote filter is server-side and honored — see the
  facet caveat in `linkedin-microservice-architecture.md`, since on LinkedIn's
  guest page the work-type facet is inert
- check whether the best fit is the user's own portfolio/company rather than a
  posting, which is often the stronger play for a junior candidate

## Separating a real gain from ordinary churn

A run that raises the junior count proves the pipeline works, not which change
caused it. Before attributing a jump to a specific edit, check whether a facet
was actually applied (`NOT applied` in the scraper log) — a yield rise alongside
an inert facet is a shorter date window plus market movement.
