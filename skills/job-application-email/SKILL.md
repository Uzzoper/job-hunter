---
name: job-application-email
description: "Apply to jobs by email: draft, HOLD, send, dedupe."
version: 1.0.0
author: jobhunter-bot
license: MIT
category: jobhunter
metadata:
  hermes:
    tags: [email-application, job-hunt, cover-letter, himalaya]
    related_skills: [company-scraper, listing-hunt, job-application]
---

## When to Use

- A listing has a real contact e-mail (a recruiting/company address) and no
  portal apply flow — the email IS the application.
- The user asks to "enviar email", "mandar currículo", "candidatar por email".
- The portal channel (`job-application`) is unavailable or the user chose email.

## When NOT to Use

- The job has a portal apply form (Gupy/InfoJobs) and the user wants the portal
  flow → `job-application`.
- The company needs research first (contacts, careers page, tech signals) →
  `company-scraper`.
- LinkedIn — never automated; the human applies.

## Procedure

1. **Analyze first** — `POST /api/jobs/{id}/analyze` with `X-Bot-Token`; the
   draft must reflect the real matched/missing skills, not a generic template.
2. **Draft personalized** — see the structure below. Honest about being júnior:
   name the shallow skills explicitly and pair each with the transferable base
   + ramp-up speed. Never inflate seniority.
3. **HOLD — print the draft verbatim and STOP.** Do not send until the user
   explicitly approves. The draft file keeps the subject on its own first line
   (`Assunto: <subject>`) as a reference header only.
4. **Subject must NOT appear in the body.** Strip the `Assunto:` header into a
   separate body file and grep the body for the subject string — a repeated
   subject reads as a template error.
5. **Send** — himalaya (command below). On success reply `EMAIL_SENT` only;
   missing config/resume → `EMAIL_TOOL_MISSING`.
6. **Record** — add the job id + company + recipient to the applied-state and
   note the send in `§ jobs-hunt`.

## Dedupe rule (standing)

Same recipient + same vacancy = ONE send, ever. Two rows for the same vacancy
(e.g. the same post re-shared by a second author) count as one — send once,
skip the duplicate's draft/analysis.

**Already sent? Record it, do not re-send.** Check the outbox first. If the
send for that recipient+vacancy is already there, the terminal action is to
write `memails/applications/<job_id>.json` for BOTH rows with `status: SENT`,
`sent_at` and the outbox record id as `evidence` — then stop. Re-sending is a
duplicate contact to the same recruiter, which is worse than not sending.

**Printing a draft is read-only.** A request to print/show the draft must
never trigger a send; the send happens only on an explicit send instruction.

## Draft structure (PT-BR, concise, non-repetitive)

1. **Opening** — name + role + one line on why THIS role (the product/stack).
2. **Match** — the concrete overlap (stack, product, what you own end-to-end).
3. **Gaps** — name the shallow skills honestly; pair each with the
   transferable base + ramp-up speed. Never inflate seniority.
4. **Logistics** — English level, remote, portfolio/GitHub.
5. **Sign-off** — name, e-mail, phone.

Standing style rules: gender-neutral (no `(a)` markers), no redundant phrases,
PT-BR, year-only dates.

**Identity fields come from the bot's profile/memory, not from a template.**
Name, e-mail, phone and links are read at draft time from the bot's own
profile/memory (the same source the SOUL placeholders resolve from); a skill
document must not carry a placeholder token for them. Omit any field the
profile does not hold — never invent or hardcode a value.

## Send command

The resume is **downloaded fresh at send time** — there is no standing local
copy. The backend profile is the single source of truth, so a stale file can
never be sent.

```bash
TOKEN=$(cat <bot-profile-dir>/api-token.txt)

# 1. pull the canonical PDF into a temp file and gate it
curl -s -o /tmp/resume_send.pdf -w 'http=%{http_code} type=%{content_type}\n' \
  -H "X-Bot-Token: $TOKEN" \
  http://127.0.0.1:8080/api/profile/resume
# gate: HTTP 200 / application/pdf / %PDF-  -> any failure: ABORT, reply EMAIL_TOOL_MISSING

# 2. send, attaching THAT temp path
~/.local/bin/himalaya message compose -a dev --send \
  -t <to> -s "<subject>" \
  --body-file /tmp/email_<id>_body.txt \
  --attach /tmp/resume_send.pdf

# 3. delete the temp right after the send
rm -f /tmp/resume_send.pdf
```

## Pitfalls

- **Never attach a standing local resume copy.** The resume has **no path
  placeholder** — it is downloaded fresh from the backend profile at attach
  time (the single source of truth), so no permanent local resume file exists
  and none is ever assumed. Download to a temp file, gate it (HTTP 200 /
  `application/pdf` / `%PDF-`), attach the temp path, and delete it right after
  the send.
- **Placeholder emails are not a channel** — `accommodations@`, `candidate_accessibility@`, `more@` are HR-system aliases, not recruiting inboxes; skip them and say so.
- **Personal Gmail as "contact"** — a recruiter's personal address (e.g. `…rh@gmail.com`) is a real channel but flag it as suspect.
- **Below-threshold scores** — a job under the profile's `min-match-score` is not a good email target; say so instead of sending a weak draft.
- **Stale listings** — the DB snapshot is old; a job showing "Applications closed" on the portal is dead even if the e-mail is present. Verify before promising a send.
- **Duplicate vacancy rows** — the ingest API dedupes by URL, not by vacancy; the same post re-shared by a second author yields two rows. One send only.
- **Draft already in the outbox** — a draft printed in an earlier session may
  already have been sent. Grep the outbox for the recipient before drafting or
  sending; if it is there, record both rows as SENT and stop.

See `references/email-application-flow.md` for the template + worked example.
