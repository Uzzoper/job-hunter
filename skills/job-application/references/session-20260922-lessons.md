# Session 2026-09-22 — Post-Quarantine Reapply & URL Truncation Truth

## Summary

Follow-up to the 2026-09-21 quarantine session. After quarantining the orphaned
job 609 records, the user asked for a full re-apply from scratch: preflight →
loop → screenshot → verdict → backend updated. This session produced three
corrections to the earlier findings.

## Correction: URL Truncation Is Display-Only (NOT a DB Bug)

The earlier `session-20260921-quarantine-divergence.md` claimed URLs were
literally truncated in the database. **This was wrong.** The `...` is a terminal
display artifact — the URLs are complete in SQLite.

```sql
SELECT id, length(url), (url LIKE '%...%') AS tem_ellipsis
FROM jobs WHERE id IN (609, 21, 68);
-- (609, 113, 0), (21, 118, 0), (68, 108, 0)  → tem_ellipsis = 0 for all
```

`curl -s -I -L <full_url>` returns HTTP 200 — the URL resolves. **Always write
the `url` field to a file and navigate from that file, never via print.**

## Correction: lifecycleState Lives in email_drafts, Not jobs

The `lifecycleState` column does NOT exist on the `jobs` table. It comes from
`email_drafts.lifecycle_state` via a LEFT JOIN in `JobWithDraftStatus`. To
update it after a manual CDP apply, use the existing endpoint:

```
POST /api/jobs/{id}/applied    (X-Bot-Token required)
→ creates/updates the EmailDraft with status=SENT
→ lifecycleState derived from EmailStatus.SENT → ApplicationLifecycle.SUBMITTED
```

There is no PATCH `/api/jobs/{id}/lifecycle` endpoint — the 500 error is expected.

## Re-Apply Workflow (Quarantine → Re-Apply)

When a job has orphaned records (`applications/{id}.json` = applied, backend =
null, no screenshot), the correct remediation is:

1. **Quarantine** — move `applications/{id}.json` and `attempts/{id}.json`
   to `quarantine/` with a reason. **Never delete** — the human may want to
   audit later.
2. **Clear backend** — if needed, delete the orphan email draft row so the
   job appears lifecycle=NULL again.
3. **Re-apply from scratch** — run the full flow: navigate to the Gupy job
   page via CDP `Page.navigate` (URL from file), handle auth gate, fill
   screening questions, finalize, screenshot, then call `POST /applied`.
4. **Verify all 4 sides agree** — backend `lifecycleState=SUBMITTED`,
   `applications/{id}.json` = applied, `attempts/{id}.json` = submitted,
   `screenshots/{id}.png` exists and is a valid PNG.

## Screening Answers (2026-09-22 Validated)

The Gupy screening question this session was "Qual sua pretensão salarial?"
(1000 char max). Answer used: "Aberto a negociação conforme mercado para cargo
júnior." — saved successfully via CDP `nativeSetter` + `input`/`change`
events. The "Salvar e continuar" button worked without native browser click
(the CDP `.click()` triggered the React form submission).

## Flow Navigation Notes (2026-09-22)

- `browser_navigate` may redirect to `/candidates/auth` (session expired) even
  when Chromium is logged in. The browser tool may be on Browserbase, not the
  local CDP. Verify with `browser_cdp(method="Target.getTargets")`.
- The "Continuar" auth gate button was matched by `x.name ===
  'saveAndContinueButton'` in one attempt; by `x.textContent.trim() ===
  'Continuar'` in another. Both work — the first match in document order wins.
- The "Responder agora" button reveals the screening form. CDP `.click()`
  triggered it successfully.
- After "Salvar e continuar" on screening, the page advanced to the
  "Apresente-se!" modal. "Finalizar candidatura" completed the application.
- Confirmation text: "Candidatura finalizada!" — inline SPA success, no URL
  change.
