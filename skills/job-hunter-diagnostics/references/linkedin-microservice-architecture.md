# LinkedIn microservice — architecture and yield diagnosis

Container `job-hunter-linkedin-scraper`, port 3000, source at
`<job-hunter-repo>/linkedin-scraper` (Node + TypeScript + Playwright).
Deployed via `docker-compose.yaml` (`build.context: ./linkedin-scraper`).

## It scrapes anonymously

`src/services/browser.ts` launches `chromium.launch({ headless: true })` and every
scraper (`src/scrapers/search.ts`, `src/scrapers/detail.ts`) calls
`this.browserManager.newContext()` for a throwaway context.

Confirm there is no session anywhere:

```bash
docker exec job-hunter-linkedin-scraper sh -lc \
  'grep -rn "storageState\|addCookies\|li_at\|userDataDir\|persist" /app/dist/'
# empty output = no stored session, by design
```

Consequence for diagnosis: never propose authentication as the remedy for poor
LinkedIn yield. There is no session to establish.

## `browser: "disconnected"` is the idle state

`launch()` is lazy and guarded (`if (this.browser && this.launched) return`), and a
`disconnected` handler only flags a crash when `launched` was true. The health route
reads the same flag, so an idle service always reports `disconnected`.

The trustworthy signals are the container logs:

```
[browser] browser not launched, auto-launching...
[browser] launching Chromium headless
[browser] browser launched
[search] initial extraction: 60 cards
[detail] jobId=..., descriptionLength=..., hasLoginWall=false
```

Failure looks like `Failed to launch Chromium: <cause>` or
`browser disconnected (possible crash)`. Anything else is a working service.

## Diagnosing low junior yield

A fetch that saves mostly non-dev or mostly senior roles is a query-shape problem.
Read these three log lines together:

```
[search] keywords="desenvolvedor", location="Brazil", geoIds="106057199", count=60
[search] pagination scroll 1/3...: found 60 cards, 0 new
[search] no new cards after scroll, stopping pagination
```

1. **One generic keyword.** With no seniority term, LinkedIn relevance-ranking puts
   senior/pleno at the top. Fix lives in the backend `application.yaml`
   (`scraper.linkedin.keywords`) plus iteration in `LinkedInProvider.java` and
   passthrough in `LinkedInScraperClient.java` — the microservice takes one keyword
   per call.
2. **Pagination stalls.** `0 new` after a scroll aborts the loop, so page 2+ is never
   reached. Junior listings live below the fold. Investigate the scroll target,
   DOM-settle wait (the list lazy-loads), and scroll distance in
   `src/scrapers/search.ts`. LinkedIn also paginates by `start=` in the URL, which
   is more robust than scrolling.
3. **Ceiling of 60.** Raise `max-jobs` only *after* pagination works, or the change
   just costs more for the same page-1 set.

## Verify the keyword list actually reaches the scraper

A config listing several keywords proves nothing on its own. The backend and the
microservice can disagree, and the symptom is silent: the config looks right, the
fetch still returns one query's worth of results.

Count DISTINCT query lines, not the number of keywords in the yaml:

```bash
docker logs job-hunter-linkedin-scraper 2>&1 | grep -oE 'keywords="[^"]*"' | sort -u
```

One line here means only the first keyword is being sent, whatever the config says.
Trace the split point in this order: the yaml value itself, the parse/iteration in
`LinkedInProvider.java` (does it loop the list or take `[0]`?), then the passthrough
in `LinkedInScraperClient.java`. A comma-joined string in yaml is one scalar — if no
code splits it on the comma, only the first term ever runs.

Pair this with a yield count so the fix is measurable, not just visible in config:

```python
pat = re.compile(r'junior|\bjr\b|est[áa]gio|trainee|intern', re.I)
hits = [j for j in jobs if pat.search(j.get('title', ''))]
```

## Pagination that works, and what it costs

A healthy paginating run looks like this — note it stops on consecutive empty
rounds rather than exhausting its budget, and the `clicked show more` note means it
clicked a real control rather than scrolling blindly:

```
[search] initial extraction: 60 cards
[search] pagination round 1/6 (clicked show more): found 70 cards, 10 new
[search] pagination round 2/6: found 70 cards, 0 new
[search] pagination round 3/6: found 70 cards, 0 new
[search] no new cards for 2 consecutive rounds, stopping pagination
```

Judge yield by `new` cards, not by `count`. A run that reaches `count=70` with
`10 new` gained little over its first page. When `max-jobs` is near the first-page
size, pagination never gets the chance to matter — fix the ceiling together with the
query list, not separately.

**Budget the wall-clock.** A single-keyword run with working pagination measured
263s. Multi-keyword is N times that, so a keyword fix without a timing budget will
blow any fetch timeout. Report the elapsed time next to the counts; treat a run over
~180s as a finding in its own right, not a detail.

## A shared read timeout silently drops the heaviest query

When a keyword fix lands, one query usually yields far more cards than the others
(the most specific compound term can return 120 while its siblings return 70). A
single read timeout sized for the *typical* call then fails only that one, and the
run still reports HTTP 200 with plausible totals.

The signature is a skip, not an exception — the fetch succeeds with a hole in it:

```
WARN LinkedInScraperClient : linkedin: skipping keyword 'desenvolvedor junior'
     after failure: I/O error on GET ... : Read timed out
```

Grep for `skipping keyword` after **every** multi-keyword run and treat any hit as a
failed validation, not a partial success. Confirm the heaviest query's card count
(`count=120` vs `count=70`) is the tell.

**Fix by separating the clients, not by raising one timeout.** Search and enrichment
have opposite profiles: a paginated search legitimately takes minutes, while
enrichment is many single lightweight calls that benefit from a tight timeout for
pacing. Give search its own `RestClient` with its own request factory (config key
alongside the shared one, e.g. `search-timeout-seconds` vs `timeout-seconds`) and
leave enrichment alone — raising the shared value to accommodate one slow call
slows every detail fetch too. After the split, a previously-dropped heaviest query
should return a higher `count` than its siblings, which is the proof it survived.

## The client giving up is not the fetch failing

A long fetch can outlive the tool call waiting on it — the HTTP client times out
first, the container keeps working. The wrapper then has no result to report, which
reads like a failure but is not one.

**Poll the container for the completion line instead of retrying.** A retry fires a
second full fetch, doubling the request load against a host that is already
throttling:

```bash
docker logs job-hunter-backend 2>&1 | grep -E "completed -|totalSaved|skipped keyword"
```

The backend logs `linkedin: completed - N saved, M with email` when it finishes.
Compute elapsed from the `Fetching jobs from source` and `completed` timestamps
rather than from the client's own timing.

## A rising `hasLoginWall=true` count is the health signal

Individually, `hasLoginWall=true` is noise on a detail fetch. Tracked **across runs**
it is the leading indicator that LinkedIn is tightening against anonymous access:
a sequence of 1 → 3 → 5 over consecutive fetches means detail descriptions are
about to start coming back empty. Count it each run and report the trend — a fix
that raises yield while the wall count climbs is buying time, not solving it.

Once it climbs, the lever is fewer requests per run (lower `max-jobs`, fewer
keywords), not faster ones.

## Forwarding a facet is not applying it

`work-type`, `seniority` and `time-range` are bound in the config and now reach the
scraper service — but a forwarded facet is only a parameter on an internal call.
Whether it actually constrains LinkedIn is a separate question, and on the **guest
SSR page the answer is usually no**.

The scraper says so itself, once per facet per keyword:

```
[search] workType="remote,hybrid" forwarded but NOT applied: no LinkedIn f_WT
         code verified on the guest SSR page
[search] seniority="entry_level" forwarded but NOT applied: no LinkedIn f_E
         code verified on the guest SSR page
```

So the read is: `work-type: remote` narrowing the result set is a **belief, not a
measurement**, until a run's log shows the facet actually applied. Grep the run log
for `NOT applied` before claiming a facet works.

**The verified-code rule.** LinkedIn's native facet codes are undocumented and
drift. A wrong code filters silently-wrong, which is worse than no filter because it
looks like it is working. Probe each code live before shipping it — same query with
and without the facet, then assert the returned cards actually honor it (remote-only
results, entry-level titles, recent dates) and diff the result-set hashes. An
unverifiable code is forwarded but unmapped, with a warning; it is never a guess.

Two code facts already settled, so do not re-derive them:

- **Time range works, under a different param than expected.** It is
  `f_TPR=r<seconds>` (`past_month` → `f_TPR=r2592000`), not an `f_TE=<seconds>` form.
  Verify with distinct `datetime` values in the results, all inside the window.
- **`f_WT` (work type) and `f_E` (seniority) are not honored by the guest page.**
  Until a logged-in session proves otherwise, plan for these two to be inert.

**Consequence for capacity planning:** the one filter that genuinely narrows
results is time. Do not promise a remote-only funnel from a `work-type` setting —
it buys noise reduction at best, and the yield gain that looks like a filter win
may be nothing more than a shorter date window plus ordinary market churn. Verify
which facet produced the change before reporting it as the cause.

## geoId

Only `106057199` returns Brazilian listings. Other geoIds return Peru, UK, or empty.
The backend forwards only the first geoId (repeated params in the URL yield zero
results). Full procedure in `linkedin-geoid-validation.md`.

## Working against LinkedIn directly

The microservice is a separate actor from any interactive browser session, but both
hit the same host. A user-imposed LinkedIn pause covers **both** — a microservice
read is a LinkedIn read. While paused, restrict yourself to `docker logs`,
`/health`, and code inspection; do not call `/api/jobs` on port 3000 or trigger
`POST /api/jobs/fetch/linkedin` on the backend.
