#!/usr/bin/env python3
"""
apply.py — job-application planner for the Hermes bot (issues #37, #38, #39, #41, #42, #45, #46).

mcp-apply-loop (docs/specs/mcp-apply-loop.md): this planner's ONLY output is the
selector-free executor INTENT (built by intent.py — identity/profile/policy/
metadata; no steps, no selectors). Every successful run prints
{"intent": {...}, "dry_run": true} — the --dry-run hint is OUTSIDE the intent
object per spec l.111. The executor derives every action from live AX snapshots
via classify.py and records outcomes via verdict.py (the sole writer of the
applied record).

Cutover packages 2-3 retired the legacy execution paths from this module:
  * package 2 (spec l.287) — no plan-time record block, no CDP-daemon delegation;
  * package 3 (spec l.291-299) — the selector model is gone: portals/*.yaml,
    helpers/*.py, build_action_plan, load_portal/load_helper and the whole
    skills/cdp-daemon skill were deleted. --emit-intent is now the default
    output (kept as an accepted flag for backward compatibility).
`--record-applied` / `--confirmed` / `--auto-apply` / `--verify-with` /
`--visited-urls` / `--current-url` remain accepted as verdict-input / executor
flags; apply.py itself NEVER writes applications/ records and never calls
job_api.api_record_applied.

Pre-flight gates, enforced before any intent is emitted:
  * portal allow-list — intent.is_supported_portal (gupy, infojobs, case-
    sensitive lowercase on purpose; LinkedIn is explicitly OUT of scope);
  * profile validation — required fields: name, email, phone, cv_path,
    cover_text;
  * refusal block (#28) — profile marked NO_APPLY / cover refuses;
  * session expiry check (#41) — an expired session stops with the
    session_expired error (exit 0) so the human can authenticate first;
  * idempotency (#27) — already-applied jobs are never re-planned.

# Issue #46 — API-first: the Job Hunter REST API is the PRIMARY job source.
# The bot plans from top-scored DB jobs (--from-api) or a specific job detail
# (--job-id <id>), prefilling jobUrl/title/company into the intent metadata via
# job_api.py. --api-base-url / --api-token / --profile-dir / --min-score /
# --fetch-if-empty configure the API call. The long-lived token is registered
# ONCE by the human (never stored in this repo). The classic --job-url flow is
# unchanged otherwise.

Stdlib only: argparse, json, os, re, subprocess, time, uuid, urllib, pathlib.
No pip dependencies.

Usage:
    python3 apply.py --job-url <url> --profile <profile.json> [--portal gupy|infojobs] \\
        [--memory-dir <dir>] [--dry-run] [--confirmed] [--record-applied]
        [--skip-session-check] [--auto-apply] [--emit-intent] [--max-steps N]
    python3 apply.py --job-id <id> --profile <profile.json> [--api-token <token>]
    python3 apply.py --check-browser [--cdp-url <url>] [--user-data-dir <dir>]

Output: JSON to stdout (executor intent, browser status, or {"error": <code>, "detail": ...}).
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from navigation import SESSION_EXPIRED_DETAIL, verify_session  # issues #38, #41

import job_api  # issue #46 — Job Hunter API as the PRIMARY job source
import intent  # mcp-apply-loop — planner-intent builder + portal allow-list

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_MEMORY_DIR = (
    Path.home() / ".hermes" / "profiles" / "jobhunter-bot" / "memails"
)
APPLICATIONS_SUBDIR = "applications"
SCREENSHOTS_SUBDIR = "screenshots"
APPLIED_STATUS = "applied"
REFUSAL_MARKER = "NO_APPLY"

# Issue #46 — Job Hunter API-first flow: base URL of the Spring Boot backend.
DEFAULT_API_BASE_URL = "http://localhost:8080"

# Issue #43 — verification methods. Default is screenshot (behavior unchanged);
# "ax" asks the executor to verify via the accessibility tree instead.
VALID_VERIFY_MODES = ("screenshot", "ax")

# Issue #39 — browser recovery defaults.
DEFAULT_CDP_URL = "http://localhost:9222"
DEFAULT_USER_DATA_DIR = "~/.chromium-profile-cdp"
CDP_RECHECK_DELAY = 3  # seconds to wait after starting Chromium before re-checking CDP


# Gupy job URLs look like https://<portal>.gupy.io/jobs/<id-slug>
JOB_SLUG_RE = re.compile(r"/jobs/([^/?#]+)")


# ---------------------------------------------------------------------------
# Issue #39 — Browser recovery: check / start / orchestrate
# ---------------------------------------------------------------------------

def check_cdp(cdp_url: str = DEFAULT_CDP_URL, timeout: int = 2) -> bool:
    """Check whether the Chrome DevTools Protocol endpoint is reachable.

    Performs a lightweight GET to ``<cdp_url>/json/version``. Returns True
    when the endpoint responds (HTTP 200), False on any connection/timeout
    error.
    """
    try:
        resp = urllib.request.urlopen(
            cdp_url.rstrip("/") + "/json/version", timeout=timeout
        )
        return resp.status == 200
    except Exception:
        return False


def start_chromium(user_data_dir: str,
                   remote_debugging_port: int = 9222) -> subprocess.Popen:
    """Launch Chromium with a persistent profile and remote debugging.

    The *user_data_dir* is expanded (``~`` → home) and created if it does
    not exist. The process runs detached (stdout/stderr discarded).

    Returns the ``subprocess.Popen`` handle so the caller can track it if
    needed.
    """
    expanded = os.path.expanduser(user_data_dir)
    os.makedirs(expanded, exist_ok=True)
    return subprocess.Popen(
        [
            "chromium",
            f"--remote-debugging-port={remote_debugging_port}",
            f"--user-data-dir={expanded}",
            "--no-first-run",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


# PT-BR user-facing message (issue #39 — user-facing strings in Portuguese).
_BROWSER_RECOVERY_LOGIN_MSG = (
    "Navegador indisponível, iniciando o Chromium. "
    "Faça login no Gupy e confirme."
)


def ensure_browser(
    cdp_url: str = DEFAULT_CDP_URL,
    user_data_dir: str = DEFAULT_USER_DATA_DIR,
    remote_debugging_port: int = 9222,
    startup_delay: float = CDP_RECHECK_DELAY,
    timeout: int = 2,
) -> Dict[str, Any]:
    """Orchestrate browser health: check → start → wait → re-check.

    Returns a status dict with a ``"status"`` key:

    * ``"ready"``              — CDP was reachable immediately.
    * ``"needs_login"``        — CDP was down, Chromium was started, CDP is
                                 now reachable but the session is fresh; the
                                 user must log in.  (Exit code 0 — not an error.)
    * ``"browser_unavailable"`` — Chromium failed to start or CDP is still
                                  unreachable after the startup delay.
                                  (Exit code 1 — real error.)
    """
    # 1. Check if CDP is already reachable.
    if check_cdp(cdp_url, timeout=timeout):
        return {"status": "ready"}

    # 2. CDP not reachable — launch Chromium.
    try:
        start_chromium(user_data_dir, remote_debugging_port)
    except Exception as exc:
        return {
            "status": "browser_unavailable",
            "detail": f"Chromium failed to start: {exc}",
        }

    # 3. Wait for Chromium to boot and open its debugging port.
    time.sleep(startup_delay)

    # 4. Re-check CDP.
    if check_cdp(cdp_url, timeout=timeout):
        return {
            "status": "needs_login",
            "detail": _BROWSER_RECOVERY_LOGIN_MSG,
        }

    return {
        "status": "browser_unavailable",
        "detail": "Chromium started but CDP is still unreachable",
    }



# ---------------------------------------------------------------------------
# Profile handling
# ---------------------------------------------------------------------------

def load_profile(path: str) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """Load a profile JSON file; returns (profile, None) or (None, error)."""
    p = Path(path)
    if not p.is_file():
        return None, {"error": "invalid_profile",
                      "detail": f"profile file not found: {path}"}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        return None, {"error": "invalid_profile",
                      "detail": f"profile is not valid JSON: {e}"}
    if not isinstance(data, dict):
        return None, {"error": "invalid_profile",
                      "detail": "profile must be a JSON object"}
    return data, None


# Required profile fields (mcp-apply-loop cutover pkg 3): the portal YAML
# mapper is retired, so the required set is now a fixed allow-list shared by
# every portal — the same fields the Gupy selector mapping used to require.
REQUIRED_PROFILE_FIELDS = ("name", "email", "phone", "cv_path", "cover_text")


def validate_profile(profile: Dict[str, Any]) -> List[str]:
    """Return the list of required profile fields that are missing/blank."""
    missing: List[str] = []
    for field in REQUIRED_PROFILE_FIELDS:
        value = profile.get(field)
        if value is None or (isinstance(value, str) and not value.strip()):
            missing.append(field)
    return missing


def check_refusal(profile: Dict[str, Any]) -> bool:
    """Guardrail #28: block when the profile is a NO_APPLY / refusal draft."""
    if profile.get("no_apply"):
        return True
    cover = profile.get("cover_text")
    if cover is None:
        return False
    return REFUSAL_MARKER in str(cover).upper()


# ---------------------------------------------------------------------------
# Job-id derivation
# ---------------------------------------------------------------------------

def derive_job_id(url: str) -> Optional[str]:
    """Derive a job id (URL slug) from a Gupy job URL, or None if impossible."""
    if not url:
        return None
    match = JOB_SLUG_RE.search(url)
    if match:
        return match.group(1)
    try:
        path = urllib.parse.urlparse(url).path.rstrip("/")
    except Exception:
        return None
    if path:
        segment = path.rsplit("/", 1)[-1]
        if segment:
            return segment
    return None


# ---------------------------------------------------------------------------
# Idempotency gate (#27) — memory record READ-ONLY.
# The applications/ record is now written ONLY by verdict.py (mcp-apply-loop,
# cutover pkg 2): the planner reads it to short-circuit duplicates.
# ---------------------------------------------------------------------------

def applications_dir(memory_dir: Path) -> Path:
    return Path(memory_dir) / APPLICATIONS_SUBDIR


def check_idempotency(memory_dir: Path, job_id: str) -> Optional[Dict[str, Any]]:
    """Return the stored application record for job_id, or None."""
    path = applications_dir(memory_dir) / f"{job_id}.json"
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None



# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

def build_error(code: str, detail: str, memory_dir: Optional[Path] = None,
                job_id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
    """Build a JSON error dict with the standard screenshot_path hint."""
    err: Dict[str, Any] = {"error": code, "detail": detail}
    if memory_dir is not None:
        shot = Path(memory_dir) / SCREENSHOTS_SUBDIR
        name = f"{job_id}.png" if job_id else "error.png"
        err["screenshot_path"] = str(shot / name)
    err.update(extra)
    return err


# ---------------------------------------------------------------------------
# CLI


# ---------------------------------------------------------------------------

def parse_args(argv: Optional[List[str]]):
    """Parse CLI flags with argparse (raises SystemExit on invalid flags)."""
    parser = argparse.ArgumentParser(prog="apply.py", add_help=False)
    parser.add_argument("--job-url")
    parser.add_argument("--profile")
    # issue #45: --portal defaults to gupy (a free string so an unknown portal
    # like "linkedin" trips our clean exit-1 unknown_portal error, not argparse
    # choices which would exit 2 and break that contract).
    parser.add_argument("--portal", default="gupy")
    # Issue #46 — API-first flow. The Job Hunter API is the PRIMARY job source:
    # --from-api plans from the top-scored DB job, --job-id from GET /api/jobs/{id}.
    parser.add_argument("--job-id", default=None,
                        help="issue #46: Job Hunter API job id; fetches the detail and prefills job_url/title/company into the intent metadata (takes precedence over --from-api)")
    parser.add_argument("--from-api", action="store_true",
                        help="issue #46: pick the top-scored job from GET /api/jobs instead of --job-url (empty list triggers a fetch — see --fetch-if-empty)")
    parser.add_argument("--api-base-url", default=DEFAULT_API_BASE_URL,
                        help=f"issue #46: Job Hunter API base URL (default: {DEFAULT_API_BASE_URL})")
    parser.add_argument("--api-token", default=None,
                        help="issue #46: Job Hunter API token (overrides JOBHUNTER_API_TOKEN env and <profile-dir>/api-token.txt)")
    parser.add_argument("--profile-dir", default=None,
                        help="issue #46: bot profile dir holding api-token.txt (default ~/.hermes/profiles/jobhunter-bot)")
    parser.add_argument("--min-score", type=int, default=None,
                        help="issue #46: minimum match score filter forwarded to GET /api/jobs (minScore)")
    parser.add_argument("--fetch-if-empty", dest="fetch_if_empty",
                        action="store_true", default=True,
                        help="issue #46: when the API list is empty, trigger a fetch and re-list (default)")
    parser.add_argument("--no-fetch-if-empty", dest="fetch_if_empty",
                        action="store_false",
                        help="issue #46: do not trigger a fetch when the API list is empty")
    parser.add_argument("--memory-dir")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--confirmed", action="store_true")
    # Cutover pkg 2 (spec l.287): --record-applied / --confirmed / --auto-apply
    # are KEPT as verdict-input flags for the MCP executor. apply.py itself no
    # longer writes applications/ records — verdict.py is the sole writer.
    parser.add_argument("--record-applied", action="store_true",
                        help="verdict-input flag (mcp-apply-loop): kept for the executor/verdict stage; apply.py itself no longer writes records")
    # Issue #42 — auto-apply: implies --confirmed for the executor/verdict stage
    # (require_confirmation_before_final_submit False). Never bypasses
    # idempotency/refusal/session safety.
    parser.add_argument("--auto-apply", action="store_true",
                        help="implied confirmed for the executor/verdict stage (never bypasses idempotency/refusal/session safety)")
    # Issue #38 flags kept for the executor. The deterministic guard
    # (guard_from_cli) was retired for the apply loop (cutover pkg 3): the
    # executor enforces auth/loop stops at runtime via classify.py +
    # stop_on_auth_url. Stateless: apply.py never tracks state.
    parser.add_argument("--visited-urls", help="comma-separated list of previously visited URLs")
    parser.add_argument("--current-url", help="the URL the browser is currently on")
    # Issue #39 — browser recovery.
    parser.add_argument("--check-browser", action="store_true",
                        help="print browser status as JSON and exit")
    parser.add_argument("--cdp-url", default=None,
                        help=f"CDP endpoint URL (default: {DEFAULT_CDP_URL})")
    parser.add_argument("--user-data-dir", default=None,
                        help=f"Chromium user data dir (default: {DEFAULT_USER_DATA_DIR})")
    # Issue #41 — session expiry gate.
    parser.add_argument("--skip-session-check", action="store_true",
                        help="skip the session expiry check (also skipped on --dry-run)")
    # Issue #43 — verification: screenshot (default) or the AX tree.
    # A free string (not argparse choices) so an invalid mode trips our clean
    # exit-2 usage error instead of argparse printing to stderr.
    parser.add_argument("--verify-with", default="screenshot",
                        help="verification method for fill/submit steps: screenshot (default) or ax")
    # mcp-apply-loop phase 3 — planner-intent emission (selector-free mode).
    # --max-steps caps the executor loop budget (default DEFAULT_MAX_STEPS).
    parser.add_argument("--emit-intent", action="store_true",
                        help="emit the selector-free executor INTENT JSON (id/url/portal/profile/policy/metadata) — accepted for backward compat; intent emission is now the default output")
    parser.add_argument("--max-steps", type=int, default=None,
                        help=f"executor loop step budget for the intent (default: {intent.DEFAULT_MAX_STEPS}; must be >= 1)")
    return parser.parse_args(argv)


def run(argv: Optional[List[str]] = None) -> int:
    """CLI entry point; returns the process exit code."""
    try:
        args = parse_args(argv)
    except SystemExit:
        # argparse already wrote the flag error to stderr; keep stdout JSON-only.
        print(json.dumps(build_error("usage", "invalid arguments")))
        return 2

    # Issue #39 — --check-browser: standalone mode, no action plan needed.
    if args.check_browser:
        cdp_url = args.cdp_url or DEFAULT_CDP_URL
        user_data_dir = args.user_data_dir or DEFAULT_USER_DATA_DIR
        status = ensure_browser(cdp_url=cdp_url, user_data_dir=user_data_dir)
        print(json.dumps(status, ensure_ascii=False))
        return 0 if status["status"] != "browser_unavailable" else 1

    memory_dir = Path(args.memory_dir) if args.memory_dir else DEFAULT_MEMORY_DIR

    # Issue #43 — validate the verification mode up front (clean JSON error,
    # keeping stdout JSON-only rather than argparse's stderr usage dump).
    if args.verify_with not in VALID_VERIFY_MODES:
        print(json.dumps(build_error(
            "usage",
            f"invalid --verify-with mode: {args.verify_with} (supported: {', '.join(VALID_VERIFY_MODES)})",
            memory_dir=memory_dir,
        )))
        return 2

    # Issue #46 — API-first: the job URL may come from the Job Hunter API
    # (--job-id detail or --from-api top-scored job) instead of --job-url.
    # --job-id is the most specific source; it wins when both are supplied.
    api_mode = args.from_api or bool((args.job_id or "").strip())
    job_url: Optional[str] = args.job_url
    job_title: Optional[str] = None
    job_company: Optional[str] = None

    if not api_mode and not args.job_url:
        print(json.dumps(build_error(
            "usage",
            "required arguments: --profile <json> plus one of --job-url | --from-api | --job-id",
            memory_dir=memory_dir,
        )))
        return 2
    if not args.profile:
        print(json.dumps(build_error(
            "usage",
            "required arguments: --profile <json> plus one of --job-url | --from-api | --job-id",
            memory_dir=memory_dir,
        )))
        return 2

    # Portal validity (mcp-apply-loop cutover pkg 1 + pkg 3): the YAML-free
    # allow-list in intent.py is authoritative. gupy/infojobs pass; linkedin,
    # unknown and case-variants fail with the same unknown_portal error
    # (exit 1). The legacy portals/*.yaml + helpers/*.py presence check was
    # retired in package 3.
    if not intent.is_supported_portal(args.portal):
        print(json.dumps(build_error(
            "unknown_portal",
            f"portal '{args.portal}' is not supported (v2 supports: gupy, infojobs)",
            memory_dir=memory_dir,
        )))
        return 1

    profile, profile_err = load_profile(args.profile)
    if profile_err is not None:
        print(json.dumps(profile_err))
        return 1

    missing = validate_profile(profile)
    if missing:
        print(json.dumps(build_error(
            "invalid_profile",
            f"missing required profile fields: {', '.join(missing)}",
            memory_dir=memory_dir,
        )))
        return 1

    if check_refusal(profile):
        print(json.dumps(build_error(
            "refusal_draft_blocked",
            "profile is marked NO_APPLY or its cover text carries the refusal marker; refusing to plan an application",
            memory_dir=memory_dir,
        )))
        return 1

    # Issue #46 — resolve the job from the Job Hunter API when API-first mode
    # is active. Every failure (missing token, 401, unknown job id, empty
    # list after fetch-if-empty) prints clean JSON and exits 1 — never a
    # traceback on the bot side.
    backend_job_id: Optional[int] = None  # issue #48 — numeric backend id carried in the intent metadata for the verdict stage
    if api_mode:
        token = job_api.resolve_token(args.api_token, profile_dir=args.profile_dir)
        if isinstance(token, dict):
            print(json.dumps(token, ensure_ascii=False))
            return 1
        base_url = args.api_base_url or DEFAULT_API_BASE_URL
        if bool((args.job_id or "").strip()):
            try:
                job_id_int = int(args.job_id)
            except ValueError:
                print(json.dumps(build_error(
                    "usage",
                    f"--job-id must be a numeric Job Hunter job id, got: {args.job_id}",
                    memory_dir=memory_dir,
                )))
                return 1
            api_job = job_api.api_get_job(base_url, token, job_id_int)
            if isinstance(api_job, dict) and "error" in api_job:
                print(json.dumps(api_job, ensure_ascii=False))
                return 1
        else:
            jobs = job_api.pick_jobs_for_apply(
                base_url=base_url,
                token=token,
                min_score=args.min_score,
                fetch_if_empty=args.fetch_if_empty,
                portal=args.portal,
            )
            if isinstance(jobs, dict) and "error" in jobs:
                print(json.dumps(jobs, ensure_ascii=False))
                return 1
            if not jobs:
                print(json.dumps(build_error(
                    "no_jobs",
                    "no jobs available from the Job Hunter API after fetch-if-empty; nothing to apply to",
                    memory_dir=memory_dir,
                )))
                return 1
            api_job = jobs[0]
        if not isinstance(api_job, dict):
            print(json.dumps(build_error(
                "api_error",
                "unexpected job payload from the Job Hunter API",
                memory_dir=memory_dir,
            )))
            return 1
        job_url = str(api_job.get("url") or "")
        job_title = api_job.get("title")
        job_company = api_job.get("company")
        backend_job_id = api_job.get("id")

    job_id = derive_job_id(job_url)
    if not job_id:
        print(json.dumps(build_error(
            "invalid_job_url",
            f"could not derive a job id from url: {job_url}",
            memory_dir=memory_dir,
        )))
        return 1

    # Issue #39 — browser recovery. When the CDP endpoint is unreachable we
    # start Chromium and hand the session back to the human for login. Runs
    # BEFORE the #41 session gate. --dry-run never touches the browser.
    if not args.dry_run:
        cdp_url = args.cdp_url or DEFAULT_CDP_URL
        user_data_dir = args.user_data_dir or DEFAULT_USER_DATA_DIR
        status = ensure_browser(cdp_url=cdp_url, user_data_dir=user_data_dir)
        if status["status"] == "needs_login":
            # Not an error: the browser session is fresh and the user must log
            # in. Exit code 0 and a recognisable status so the bot can ask the
            # human to authenticate and then retry.
            print(json.dumps({
                "ok": True,
                "status": status["status"],
                "detail": status["detail"],
            }, ensure_ascii=False))
            return 0
        if status["status"] == "browser_unavailable":
            print(json.dumps(build_error(
                "browser_unavailable",
                status.get("detail", "Chromium is not running and could not be started"),
                memory_dir=memory_dir,
            )))
            return 1

    # Issue #41 — session expiry gate (pre-flight). When the browser is on a
    # login/auth page (session expired), NO intent is emitted: the bot stops
    # with the session_expired error (exit 0) and asks the human to authenticate
    # first. Skipped on --skip-session-check and --dry-run. Runtime auth stops
    # mid-loop remain the executor's job (classify.py + stop_on_auth_url).
    session_check_enabled = not (args.skip_session_check or args.dry_run)
    if session_check_enabled:
        session_result = verify_session(
            args.current_url, login_hint_url=job_url,
        )
        if session_result["session"] == "expired":
            print(json.dumps(build_error(
                "session_expired",
                SESSION_EXPIRED_DETAIL,
                memory_dir=memory_dir,
                job_id=job_id,
                login_url=session_result.get("login_url") or job_url,
            )))
            return 0

    existing = check_idempotency(memory_dir, job_id)
    if existing and existing.get("status") == APPLIED_STATUS:
        print(json.dumps(build_error(
            "already_applied",
            f"application already recorded for job id '{job_id}'; refusing to plan a duplicate (guardrail #27)",
            memory_dir=memory_dir,
            job_id=job_id,
            existing_record=existing,
        )))
        return 1

    # mcp-apply-loop — planner-intent emission, the ONLY output since cutover
    # pkg 3 (spec l.291-299): the selector-free contract (intent.py) carries
    # only the job, portal, applicant profile and policy gates; the executor
    # derives every action from live AX snapshots via classify.py. All gates
    # above (profile validation, refusal, session expiry, idempotency) still
    # apply. --emit-intent stays accepted as an explicit switch — its behavior
    # is now the default. Never writes a record — verdict.py is the sole writer
    # of the applied record.
    # Spec l.111: --dry-run is an execution hint OUTSIDE the intent object.
    intent_obj = intent.build_intent(
        intent_id=str(uuid.uuid4()),
        job_id=job_id,
        job_url=job_url,
        portal=args.portal,
        profile=profile,
        require_confirmation=not (args.confirmed or args.auto_apply),
        max_steps=args.max_steps if args.max_steps is not None else intent.DEFAULT_MAX_STEPS,
        job_title=job_title,
        job_company=job_company,
        backend_job_id=backend_job_id,
        api_base_url=args.api_base_url if api_mode else None,
    )
    payload: Dict[str, Any] = {"intent": intent_obj}
    if args.dry_run:
        payload["dry_run"] = True
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(run())