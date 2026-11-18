---
name: job-hunter-repo-workflow
description: Use when changing or reviewing the job-hunter repo.
version: 1.0.0
author: jobhunter-bot
category: jobhunter
tags: [job-hunter, git, code-review, ci, skills]
metadata:
  hermes:
    tags: [job-hunter, git, code-review, ci, skills]
    related_skills: [listing-hunt, job-application]
---

# Skill: Job Hunter repo — changes, sync, PR verification

> Scoped to `<job-hunter-repo>` (Spring backend, Rust CLI, bot skills).
> The repo's own `AGENTS.md` governs code rules, commit convention and TDD; this
> skill carries the **verification and repo-hygiene workflow** that AGENTS.md does
> not: how to prove a change is good, and how not to damage the repo on the way.

## When to use

- Reviewing or approving a PR in `job-hunter` — verify the body's claims by execution.
- Opening a PR: branch off `dev`, commit, push, `gh pr create`.
- Syncing or editing a bot skill under `skills/` (repo-first mirror rules).
- Deploying a backend slice and validating a new endpoint contract —
  `references/deploy-and-validate.md`.
- Any `git commit` / `git push` in this repo — identity and signing first.

## Always-on rules

- **Check git identity BEFORE the first commit.** Run
  `git config user.name && git config user.email` (and the signing setup:
  `commit.gpgsign`, `user.signingkey`, `gpg.format`). This host's *global*
  identity is a placeholder (`{{USER_NAME}} <{{EMAIL}}>`) with no signing configured,
  so an unchecked `git commit` lands with the wrong author/committer and
  **unsigned**, while the owner's commits are SSH-signed and show "Verified" on
  GitHub. Set the identity explicitly (repo-local or global) before committing;
  the least invasive form is per-commit —
  `git -c user.name="<owner>" -c user.email="<owner>" commit` — so a
  placeholder global config cannot leak into a PR. If the host has **no signing
  key** (`~/.ssh/*.pub`, `user.signingkey`), the commit will be unsigned: say so
  in the report instead of letting it pass as a normal signed commit.
- **Never commit on `main`.** Branch → push → PR. Feature PRs target `dev` (that
  is where the owner's work accumulates); `dev → main` promotions are separate PRs.
- **Verify by execution.** A PR body is a claim list, not evidence. Reproduce each
  claim with a command and report it verified / not verified / contradicted.
- **The owner works through the bot, not the CLI binary.** When a change touches
  the bot's own workflow (skills, gates, apply flow), exercise it through the
  agent; do not propose driving `jh-cli` to "test" it.
- **Report every item of a multi-part request explicitly.** One line per item,
  each carrying its own evidence (command, exit code, outcome). An omitted item
  reads as "not done" — never compress a multi-item ask into a single
  all-clear, and never answer a specific item with a general "it's all fine".

## Procedure — verify a PR before approving

1. `git fetch origin`; note the PR's base and head.
2. **Work on a detached worktree of the head**, never by checking out a shared
   branch — the main working tree carries uncommitted noise that pollutes
   `git diff` and makes the change set look bigger than it is:
   `git worktree add --detach <dir> origin/dev`
3. Check the body's claims mechanically:
   - file list vs the claims: `git diff --name-only origin/main...origin/dev`
   - "no backend/Java change": same command filtered for
     `src/main|src/test|\.java|pom\.xml` → expect 0 hits
4. Run the **exact CI commands**, not approximations:
   - `cargo test --manifest-path cli/Cargo.toml`
   - `cargo clippy --manifest-path cli/Cargo.toml -- -D warnings`  ← no `--all-targets`
   - Java: `./mvnw test --batch-mode`
5. **Triage every failure as pre-existing vs introduced.** A failure in a file the
   PR does not touch — identical on both sides — is not the PR's defect:
   compare `git show origin/main:<path>` with `git show origin/dev:<path>` and
   cross-check the `--name-only` list. Say so explicitly instead of reporting it
   as a blocker.
6. Report per claim: verified / not verified / contradicted, each with the
   command and its exit code.

## Pitfalls

- **`git pull` with no arguments fails in this repo.** Local branches carry no
  upstream tracking, so a bare `git pull` aborts ("Need to specify how to
  reconcile divergent branches" / "Not possible to fast-forward"). Always name
  the remote: `git pull --ff-only origin <branch>`.
- **Prove orphan commits superseded before discarding them.** A local branch can
  carry commits that exist on no remote, and `git reset --hard origin/<branch>`
  destroys them silently. List them
  (`git log --oneline origin/<branch>..<branch>`), check
  `git branch -r --contains <sha>` (empty = on no remote), find the upstream
  commit that covers the same change, and `git diff <orphan> <upstream>` the
  touched paths — an empty diff means the same fix under another commit message,
  so the reset loses nothing. Any real delta → stop and report it before
  resetting.
- **`cargo clippy --all-targets` fails on this repo while CI is green** (a test
  file the PR never touches). CI lints only the default targets — run CI's exact
  command before calling anything a defect.
- **Never use `cargo fmt --check` as a gate here**: `rustfmt.toml` uses
  nightly-only options, so stable rustfmt reformats files all over the tree
  (including untouched ones). The repo is not fmt-clean under stable rustfmt.
- **A red build may be environmental, not a code defect.** A Rust build needs
  well over 1 GB of scratch; on a small `tmpfs` it dies with
  `Disk quota exceeded`. Re-run on a filesystem with room *before* attributing
  the failure to the change — and put the detached worktree there in the first
  place: `git worktree add --detach <dir-on-root-fs> origin/<branch>`, not under
  `/tmp`, which on this host is a small `tmpfs` that a full `target/` tree
  exhausts. Check with `df -h` before building.
- **Clean up the worktree** (`git worktree remove --force` + `git worktree prune`):
  a detached worktree holds a full `target/` tree.

## Syncing `skills/` (repo-first)

- `skills/<name>/` in the repo is the product; `scripts/install-bot-skills.sh`
  copies **repo → profile** (`SKILL.md`, `*.py`, `*.sh`, and subdirectories).
  It installs only directories that contain a `SKILL.md` and skips `*_test.py`
  and `__pycache__` — a stray directory without a `SKILL.md` is inert, and test
  files present in the repo are expected to be absent from the profile.
- Never hand-edit the installed copy under `<bot-profile-dir>/`
  as a source of change, and never run the installer to "fix" a difference — it
  silently erases live-only edits. Reconcile deliberately, then install.
- **A skill with no repo counterpart is invisible to the installer.**
  `install-bot-skills.sh` copies repo → profile, so a skill that exists only
  under `<bot-profile-dir>/skills/` is never updated by a real
  install — and the drift inventory cannot compare it either, because it diffs
  against `git show origin/<base>:skills/<name>/SKILL.md`, which does not exist
  for it. Hand-editing that live copy is the only way it changes; decide
  deliberately whether it should be versioned into the repo instead.
- When mirroring a skill, copy the **whole skill dir** (`SKILL.md` *and*
  `references/`): a `references/…` citation in `SKILL.md` is dangling after install
  unless that directory is committed too.
- Before committing a mirrored skill, diff it against the live copy and report
  any live-only lines the repo is about to lose.
- **Live can be ahead as well as behind.** Diff live against the repo and split
  the differences into *repo-ahead* (lines only the repo has — keep them) and
  *live-ahead* (lines only live has — re-apply them). Start from the **repo**
  copy and re-apply only the live-only edits; never revert a repo-ahead line
  just to match live wording. Report the split before editing anything.
  When the repo is **strictly behind** — no repo-only line that live lacks — a
  wholesale `cp` of the live file is allowed, but prove it first: list the
  dev-only lines and confirm each one is an older wording the live text
  supersedes, so the copy loses nothing.
- **Prove the installer without touching live.** `install-bot-skills.sh`
  honours `HERMES_BASE`; run it against a throwaway base
  (`HERMES_BASE=<scratch> bash scripts/install-bot-skills.sh`) and then `cmp`
  every installed `SKILL.md` against its repo source. That exercises the copy
  path — including which subdirectories and helpers get installed, and which
  test files are skipped — with zero risk to the profile.
- **Resolve every citation.** A `SKILL.md` naming another skill or a
  `references/<file>.md` must point at something that exists: grep the cited
  names and confirm the target is present in the repo or the profile before
  committing. A cited skill that exists nowhere is a dangling pointer the next
  reader will trip on.
- **Gate bulk additions.** A `references/` catch-up (or any multi-file
  addition) is its own commit, approved from a report first: per file, name,
  byte size, first lines, a secret grep, **and an owner-path grep**. Skill docs
  must not hardcode an owner-machine path — genericize every `/home/<user>/…`
  hit to a placeholder (`<job-hunter-repo>/.env`, `<resume-dir>/…`,
  `~/.hermes/profiles/…`) the same way the audit genericized `cv_path`, then
  re-grep to zero before committing. Commit only the approved files.
- **Inventory before calling it drift.** Compare each skill against
  `git show origin/<base>:skills/<name>/SKILL.md` (md5), byte-compare the
  non-test `*.py`/`*.sh` helpers, and run `git status --short skills/` — a file
  sitting in the working tree is not necessarily committed, so an untracked
  `references/` *looks* installed while existing in no branch.

## Installing to the live profile (the one real run)

- **The installer copies the WORKING TREE — not a branch, not a remote.** Before
  the real run, prove the tree holds the intended upstream content:
  `git rev-parse HEAD` vs `git rev-parse origin/<branch>`. A local branch left
  behind, or one carrying its own commits, installs *its own* content and the
  run still exits 0. Trust the `Installed N skill(s)` line against the expected
  count: a skill missing from the tree, or a stale `SKILL.md`, is invisible
  until you compare — and the run silently overwrites the live copy with it.
- **Never `git pull` a diverged local branch to fix this.** Detach on the remote
  ref instead: `git checkout origin/<branch>`. Pulling a branch with unmerged
  local commits either aborts or folds them into the install tree.
- **Verify the real install, not only the sandbox run:** `cmp` every installed
  `SKILL.md` against its repo source, count each `references/` directory, and
  list profile files the repo does not have. Expected extras are runtime
  material (`memails/`, `.env`, the live resume) plus the `__pycache__` the
  installer's own `py_compile` step creates — clean that up.
- **`SOUL.md` is never overwritten**: the installer skips it when a live file
  exists. Template changes to SOUL are manual — back up the live file, then
  edit it by hand. The installer will not carry them.
- **The skill Python suite has no project venv** (`pytest` is absent from both
  the system python and the Hermes venv, and the repo ships no requirements
  file). Run it from a disposable venv, and never install into the Hermes venv:
  `/usr/bin/python3 -m venv <scratch>/pytestenv && <scratch>/pytestenv/bin/pip
  install pytest && <scratch>/pytestenv/bin/python -m pytest skills/job-application/ -q`.
