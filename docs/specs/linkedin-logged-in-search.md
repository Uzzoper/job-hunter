# Logged-in LinkedIn job search (issue #167)

Status: **proposed** · Issue: #167 · Branch: _to be created_

## 1. Context

The guest search microservice (`linkedin-scraper`) returns ~60–120 cards per
query (6 pagination rounds, `MAX_PAGINATION_ROUNDS`). The unique pool per run
tops out at ~60 URLs (§6.1/§6.3 of `linkedin-search-coverage.md`) because
terms overlap heavily. The dev track is saturated (`saved ≈ 0`).

A read-only probe (2026-11-23, issue #167 comment) on an **authenticated**
session measured:

| termo | guest cards | logged-in cards | f_WT=2 (remoto) | f_E=2 (entry) |
|---|---|---|---|---|
| analista de dados junior | ~60 | ≥425 | 100% remoto (25/25) | pass-through (ignorado) |
| data analyst | ~60 | ≥425 | 100% remoto (26/26) | pass-through (ignorado) |
| power bi | ~60 | ≥925 | 100% remoto (25/25) | pass-through (ignorado) |

- **`f_WT=2` (remote) is honored** logged in — 100% remote cards across all
  3 terms. This alone gives 450 remote data vacancies from a single term vs 2
  that the current funnel sees.
- **`f_E=2` (entry-level) is ignored** — distribution identical to baseline.
  Ships as pass-through; seniority gate stays in the normalizer
  (`SENIOR_EXCLUDE_PATTERN`).
- **No CAPTCHA/checkpoint** in ~50 pages navigated in a single session.
- **Pagination is URL-based:** clicking "Próxima" keeps the stale DOM (0 new
  cards). Only direct navigation with `&start=N` re-renders. Implementers
  must use `page.goto`/`page.navigate`, not click.
- The only logged-in LinkedIn session today is the **posts ingest** lane
  (`linkedin-posts-ingest.md`): the bot scrapes the feed and pushes via
  `POST /api/jobs/ingest/linkedin-post`. There is no logged-in **job search**
  path.

## 2. Problem

Guest search caps at ~60–120 cards and ignores `f_WT`/`f_E`. The dev track is
saturated. The data track has scarce supply (9 titles, 1 eligible so far).
A logged-in session could unlock ~10× more cards and server-side remote
filtering — but only if the session is stable and the implementation avoids
the coupling pitfalls of the guest merge loop.

## 3. Proposal

A **separate ingest lane** for logged-in job search, mirroring the posts ingest
pattern:

1. The bot (authenticated) navigates to `/jobs/search` with the configured
   keywords + `f_WT=2` + `f_TPR=past_month`, paginating via `&start=N`.
2. Extracts job cards (title, company, location, url, postedAt) from the DOM
   using the stable selector `componentkey="job-card-component-ref-<id>"`.
3. Pushes batches to a new backend endpoint `POST /api/jobs/ingest/linkedin-search`
   (bot token auth, same as posts ingest).
4. Backend validates, normalizes (keyword gate, seniority exclude, date window),
   dedupes by URL, and saves with source `linkedin-search`.
5. Saved jobs flow through analysis/e-mail exactly like scraper-sourced jobs.

**The logged-in lane stays OUTSIDE the guest merge loop.** It does not share
the per-keyword quota (`per-keyword-limit`) or the `max-jobs` cap — those are
guest-specific knobs. The logged-in lane has its own batch size and its own
dedupe.

## 4. Acceptance criteria

- [ ] Bot extracts ≥ 200 unique job cards from a single `/jobs/search` query
      while authenticated (measured, not assumed — probe confirmed ≥425 for
      `analista de dados junior`).
- [ ] `f_WT=2` (remote) is honored (probe confirmed: 100% remote cards).
- [ ] `f_TPR` (date window) is verified live before freezing (probe needed —
      expected honored, same as guest).
- [ ] `f_E=2` (entry-level) ships as pass-through (probe confirmed ignored).
      Seniority gate stays in the normalizer (`SENIOR_EXCLUDE_PATTERN`).
- [ ] Pagination uses direct URL navigation (`&start=N`), not clicking
      "Próxima" (DOM keeps stale cards on click).
- [ ] Session expiry is detected and surfaced (no silent empty results);
      guest fallback activates automatically.
- [ ] No credentials stored in the repo; session lives in bot runtime only
      (consistent with the bot-skills "repo is source of truth" policy).
- [ ] A dedicated LinkedIn account is used — never the owner's personal one.
      Account warm-up (profile, connections, activity) completed before
      enabling the lane.
- [ ] Full test coverage for the new ingest path (unit + integration),
      mirroring `linkedin-posts-ingest.md` scenario structure.
- [ ] Spec written and approved before any code (AGENTS.md SDD rule).

## 5. Non-goals

- Replacing the guest microservice entirely (keep as fallback).
- Scraping company pages or "jobs you may be interested in" (separate effort).
- Automatic credential rotation / 2FA handling beyond a manual re-login prompt.
- Applying `per-keyword-limit` or `max-jobs` to the logged-in lane.

## 6. Open questions

- Is the yield gain (guest ~60 → logged-in ~450+) worth the operational cost
  (session expiry, CAPTCHA, account ban risk) for a personal bot?
- Should the logged-in search target the data track (where supply is scarcer)
  rather than dev (already saturated)?
- How does the logged-in lane interact with the per-keyword quota and dedupe
  logic? (Recommendation: separate ingest, no shared quota.)

## 7. Implementation notes

- **Account:** dedicated LinkedIn account, warmed up (profile, 50+ connections,
  some activity) before enabling. Never the owner's personal account.
- **Browser:** reuse `BrowserManager` from `linkedin-scraper/src/services/browser.ts`.
  New context with stored cookies (Playwright `storageState`).
- **Pagination:** `page.goto('/jobs/search?...&start=N')` for each round.
  Do not click "Próxima".
- **DOM selector:** `componentkey="job-card-component-ref-<id>"` (stable).
- **Facets:** `f_WT=2` (remote, honored), `f_TPR=past_month` (expected
  honored), `f_E=2` (pass-through, do not rely on it).
- **Seniority:** `SENIOR_EXCLUDE_PATTERN` in the normalizer remains the real
  gate (pleno/sênior/expecialista dropped).
- **Session expiry:** detect login wall (reuse `LOGIN_WALL_KEYWORDS` from
  `detail.ts`); surface as error, activate guest fallback.
- **Ingest endpoint:** mirror `POST /api/jobs/ingest/linkedin-post` contract
  (bot token auth, validation, dedupe, AI classification).

## Related

- `docs/specs/linkedin-search-coverage.md` — coverage A/B and saturation finding
- `docs/specs/linkedin-search-facets.md` — facet verification (Amendment A/B)
- `docs/specs/linkedin-posts-ingest.md` — existing logged-in post ingest lane
- Issue #165 — LinkedIn search coverage (closed, merged via PR #164)
- Probe results: issue #167 comment (2026-11-23)
EOF
echo "spec written"