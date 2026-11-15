# Job-alert digests (saved searches → e-mail)

Third LinkedIn channel: the human creates saved searches (job alerts) with
daily e-mail digests; the bot only READS the digest and reports numbers.
Never create/edit alerts, never touch voyager/CSRF, never open linkedin.com
for this — the digest is the only input.

## Pilot outcome — channel DEAD

Searched every folder (Inbox, Spam, Importante, Com estrela, Todos os e-mails)
for the alert subjects → **0 digests**. The LinkedIn account's e-mail is not
this mailbox, so the digests never arrive. Do not re-run the pilot; Playwright
(feed + jobs) is the only LinkedIn path.

## himalaya commands (no parser, no new code)

```bash
~/.local/bin/himalaya mailbox list -a dev          # v2.x: `mailbox`, not `folder`
~/.local/bin/himalaya envelope list -a dev -s 30  # recent mail, table
~/.local/bin/himalaya envelope search -a dev -s 30 subject <term>
~/.local/bin/himalaya envelope search -a dev -m "[Gmail]/Todos os e-mails" -s 30 subject <term>
~/.local/bin/himalaya message read <id>
```

- The search query DSL is positional: put OPTIONS BEFORE the query
  (`subject vagas -s 30` parses `-s` as part of the query and errors).
- Gmail folder aliases must be the RAW mailbox id (`[Gmail]/Todos os e-mails`);
  the localized name fails with `IMAP SELECT failed: NO Unknown Mailbox`.
- Search every folder (Inbox, Spam, Importante, Com estrela, Todos os e-mails)
  before concluding the digests are absent — Gmail categories are not mailboxes.

## Pilot report shape (numbers only)

Per digest: `date | alert keyword | jobs listed | dev-looking count | NEW vs bank`.

- dev-looking = title heuristic (dev/desenvolved/software/backend/front/java/
  python/node/react/programador/qa/devops/.net/php, minus pleno/sênior/III/IV).
- NEW vs bank = `GET /api/jobs` and compare job view URLs — no page opens,
  no ingest.

## Blocker rule

If the digests are NOT in this mailbox (the LinkedIn account uses a different
e-mail), STOP and report that as the blocker — do not try to fix it yourself.
