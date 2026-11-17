# Bounded validation runs

A validation request arrives with explicit limits — one fetch, a time ceiling, a
fixed checklist, "no commit", "stop even if it hasn't converged". Treat each as a
hard stop, not a suggestion.

## When told to stop, report — do not route around the blocker

A stalled instrument mid-task invites trying the next tool, the next port, a
hand-rolled script. That is the failure mode to resist: a user watching a
10-minute budget burned by a fallback attempt is worse served than a clean
partial result. The valuable output of a bounded run is the honest number plus
the exact blocker, and that is only worth anything if the run actually stopped.

Deliver what exists, name what is missing, and stop. Concretely: report the
elapsed time when a fetch outlives your client timeout (the container kept
working — the completion line is in its log, and retrying would double the load);
report a partially-applied change as partially applied; report an unreachable
instrument as unreachable instead of substituting a different one. Note which
alternative you did **not** take and why, so the user can direct the next move.

Skipping instrumentation mid-run also means skipping the deliverable. When a run
cannot produce one required field, write the file with the fields you do have
rather than leaving the work unrecorded.

## Silence is read as a hang

A long validation run produces no output between tool calls, and a user watching
it cannot tell a slow fetch from a stalled agent. Land the run in one message
with every number inline — repo state, build, smoke, fetched/saved, the yield you
measured, elapsed time, and the divergence — rather than dribbling partial
reports across several turns. When a step will obviously outlive a client
timeout, say so *before* waiting on it, so the wait is expected rather than
suspicious.

## The deliverable is a comparison, not a verdict

The user is deciding whether to trust a change; give them the before/after pair
for the metric they care about and let the delta speak. "Fetched 45, saved 18,
392s, eleven of eighteen listings are on-site" answers the question. "Looks good"
does not, and a run that only reports the improvement while the regressions sit
in the same log reads as advocacy.

## Never report a config value as proof it took effect

A yaml that lists nine keywords tells you only what was written; the fetch tells
you what ran. After any provider change, confirm the effect from the runtime
side — distinct `keywords="..."` lines in the scraper log, the yield count
(junior/estágio in title), and elapsed seconds next to the counts. A keyword list
that never gets split into separate queries is a silent no-op: the config reads
correct, the fetch returns page one, and nothing errors.

## Do not commit or push as part of a validation run

Validating someone else's commits means pull, rebuild, verify, report. Adding a
commit turns a read-only check into a branch you now own.

## Report your own analysis bugs in the report

When a diagnostic you ran mid-session is wrong — a diff with a key-type bug, a
mis-scoped regex, a count computed on the wrong set — say so explicitly in the
report, show the corrected number, and label which figure it replaces. A silently
corrected number leaves the user unable to tell which of your figures to trust.