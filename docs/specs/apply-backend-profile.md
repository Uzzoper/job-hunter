# Apply & email flows consume the backend profile (issue #92, slice c)

> **Status:** proposed (awaiting approval — no code without it).
> Foundation: `bot-resume-retrieval.md` (slice a, read side) and
> `resume-preview-commit.md` (slice b, write side). This slice closes #92:
> the apply skill consumes the backend profile/PDF and the hand-maintained
> local resume copy — with the INTERIM refresh rule — ceases to exist.

## Problem

- The apply and email flows run on a **persistent local resume copy**
  (`~/.hermes/profiles/jobhunter-bot/{{RESUME_FILENAME}}`), refreshed by the
  INTERIM step-0 rule (`SKILL.md` "Interim: resume refresh before any
  attachment"). The stale-file trap happened for real (14/11: local copy was a
  Sep-16 build, 5.7 KB, while the backend served the Oct-3 canonical build,
  24.8 KB) — the point-in-time size/md5 gate keeps stalling batches and must
  be manually re-bumped after every legitimate resume update.
- `profile.json` duplicates backend data: `name`/`email`/`phone` in a local
  file drift from the users row and the profile the owner corrects in chat
  (slice b onboarding). #92 acceptance: the apply flow should work from the
  backend profile and PDF.
- The intent contract still carries `resume_path` (`intent.py:94` maps it from
  `cv_path`), pointing the executor at the local copy.
- `mcp-apply-loop.md:93` shows an owner-specific absolute path as the example
  `resume_path` (full `/home/<user>/…` form) — violates the repo rule that
  user-specific paths go through placeholders.

## Contract

### Backend — no changes

Slice (a) delivered the read side and slice (b) the write side; nothing new:

- `GET /api/profile` — profile facts with non-null `accountIdentity`
  (name/email from the users row), contacts, skills, projects.
- `GET /api/profile/resume` — the canonical PDF (`application/pdf`).
- `POST /api/profile/resume/preview` → `commit` — the confirmed update path
  (onboarding section in the skill, landed by #138).

### Planner (`apply.py`) — profile loses `cv_path`

- `REQUIRED_PROFILE_FIELDS` becomes `("name", "email", "phone", "cover_text")`.
- The bot composes the profile at plan time: `name`/`email`/`phone` from
  `GET /api/profile`, `cover_text` stays bot-side (apply-specific text, not
  backend data). `apply.py` stays HTTP-free — the bot fetches and passes the
  JSON (same pattern as `--fetch-if-empty` triggering a bot-side fetch).
- A `cv_path` key in an old profile file is tolerated but ignored
  (back-compat; never required again).
- The emitted intent **no longer carries `profile.resume_path`**
  (`intent.py` drops the field and its NotRequired entry; the copy/normalize
  loop at `:309` drops the key).

### Executor (MCP loop) — attach-time download, no persistent copy

Every action that uploads the resume downloads it fresh, right before the
upload step:

```bash
tmp=$(mktemp --suffix=.pdf "$XDG_RUNTIME_DIR")   # or the bot-profile tmpdir
curl -sS -o "$tmp" -H "X-Bot-Token: $BOT_SERVICE_API_KEY" \
  "http://127.0.0.1:8080/api/profile/resume"
```

- Sanity gate on the download (unchanged checks, minus the point-in-time
  constants): `200` / `content_type=application/pdf` / magic bytes `%PDF-`.
  The **size/md5 constant row is deleted** — a fresh download cannot go
  stale, so there is nothing to gate against a pinned build anymore.
- Gate failure aborts the action (same envelope as today). There is no
  previous local file to keep — that concept is gone.
- The temp file is deleted after the action completes (success or failure).

### Email flow (`bot-profile/SOUL.md` template)

- `{{RESUME_FILENAME}}` placeholder retires. The himalaya compose lines
  (`:79`, `:83`) are rewritten: download to a temp path via the endpoint
  above, attach that path, delete after send. No persistent copy is kept.

### Onboarding interplay (slice b, unchanged)

- Profile updates in chat go through preview → confirm → commit (the #138
  onboarding section). This slice only changes how applies and emails
  **read**; it does not touch the write path.

### Specs & docs cleanup

- `mcp-apply-loop.md`: `profile.resume_path` removed from the intent table
  and the example; the example path is hygienized (generic placeholder, no
  owner paths).
- `SKILL.md`: step-0 INTERIM rule and the whole "Interim: resume refresh"
  section **deleted** (its own text mandates this); `Inputs` table loses the
  `cv_path` row and the profile JSON example drops it; the onboarding section
  stays as the only resume-update path.
- `bot-resume-retrieval.md` / `resume-preview-commit.md`: status notes point
  here (the write-route thread and the staleness digest-echo half are closed
  by (b); the local-copy drift kill is this slice).
- `references/`: verified no `cv_path`/`resume_path` citations exist — no
  reference files need edits.

## Design decisions

- **Temp download instead of a persistent copy** — browser form uploads and
  himalaya `--attach` need a real path; download-on-demand + delete removes
  the staleness class of bug structurally (no copy can go stale if none
  persists).
- **`apply.py` stays HTTP-free** — the bot orchestrates API calls and passes
  artifacts; the planner remains a pure function of its inputs (established
  with `--fetch-if-empty`).
- **`cover_text` stays bot-side** — it is per-application text, not backend
  profile data; no backend model change is proposed.
- **No draft/persistence of the downloaded PDF** — the commit flow (b) owns
  profile writes; reading twice per batch (plan + attach) costs two cheap
  GETs, no AI calls.

## Test plan (bot-side pytest venv, existing suite)

- `apply_test.py`: `validate_profile` requires exactly the 4 fields
  (missing `cv_path` → no longer reported); a profile carrying a legacy
  `cv_path` validates without error.
- `intent_test.py` (7 current `cv_path`/`resume_path` references): intent
  contains no `resume_path`; profile mapping no longer reads `cv_path`.
- `answer_test.py`: the "currículo" chat aliases no longer map to a profile
  field (retired with `cv_path`).
- `navigation_test.py` / `ax_tree_test.py`: upload-step expectations updated
  to the executor-side temp path.
- Skill suite green (426+ tests) in the bot venv before install.

## Rollout

1. Spec approved → TDD on this branch (RED → GREEN per file above).
2. Repo-first PR → owner merge → `scripts/install-bot-skills.sh` on the VM
   (propagates skill + SOUL template; SOUL placeholder refresh happens there).
3. INTERIM section deletion lands with the install; step-0 rule ceases.
