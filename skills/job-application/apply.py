#!/usr/bin/env python3
"""
apply.py — structured job-portal application planner for the Hermes bot (issues #37, #38, #39, #41, #42, #45).

Plans a portal application as an ordered ACTION PLAN (JSON) that the bot executes
through its browser tool. This script validates inputs, enforces guardrails
(#27 idempotency, #28 refusal), checks/recovers the browser session (#39),
detects an expired session before any fill (#41), emits a single batch
fill_form step (#42), supports an --auto-apply mode that implies
confirmation, skips the manual confirm_checkpoint, and keeps the screenshot +
record audit trail (#42). Domain-specific portal helpers (#45) turn the portal
YAML mapping into the flow steps (click_apply_button → fill_form →
handle_cover_letter → submit with always-gated confirmation).

# Issue #45 — domain-specific portal helpers: apply.py loads the per-portal
# YAML mapping (portals/<portal>.yaml) AND the matching helper module
# (helpers/<portal>.py) by name; unknown portals error cleanly (unknown_portal).
# Supported portals: gupy, infojobs. LinkedIn is explicitly OUT of scope (manual
# flow + the separate Node.js scraper microservice) — no linkedin helper/YAML.

Stdlib only: argparse, json, os, re, subprocess, time, urllib, pathlib.
No pip dependencies.

Usage:
    python3 apply.py --job-url <url> --profile <profile.json> [--portal gupy|infojobs] \\
        [--memory-dir <dir>] [--dry-run] [--confirmed] [--record-applied]
        [--skip-session-check] [--auto-apply]
    python3 apply.py --check-browser [--cdp-url <url>] [--user-data-dir <dir>]

Output: JSON to stdout (action plan, browser status, or {"error": <code>, "detail": ...}).
"""

import argparse
import importlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from navigation import MAX_VISITS, SESSION_EXPIRED_DETAIL, guard_from_cli, verify_session  # issues #38, #41

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_PORTALS_DIR = Path(__file__).resolve().parent / "portals"
DEFAULT_HELPERS_DIR = Path(__file__).resolve().parent / "helpers"  # issue #45
DEFAULT_MEMORY_DIR = (
    Path.home() / ".hermes" / "profiles" / "jobhunter-bot" / "memails"
)
APPLICATIONS_SUBDIR = "applications"
SCREENSHOTS_SUBDIR = "screenshots"
APPLIED_STATUS = "applied"
REFUSAL_MARKER = "NO_APPLY"

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
# Flat-YAML mini-parser (subset used by portal files, no external deps)
# ---------------------------------------------------------------------------

def parse_yaml_flat(text: str) -> Dict[str, str]:
    """Parse a flat subset of YAML (`key: value` lines) into a dict.

    Supports blank lines, `#` comments (full-line or trailing when preceded by
    whitespace), double/single-quoted values, colons inside quoted values, and
    escaped double quotes inside double-quoted values. Keys may be dotted; the
    caller can nest them with nest_dotted().
    """
    result: Dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, value = _split_yaml_line(stripped)
        if key is None:
            continue
        result[key] = value
    return result


def _split_yaml_line(line: str) -> Tuple[Optional[str], str]:
    """Split `key: value` at the first colon outside quotes."""
    in_quote = False
    quote_char = ""
    for i, ch in enumerate(line):
        if ch in ('"', "'"):
            if not in_quote:
                in_quote = True
                quote_char = ch
            elif ch == quote_char:
                in_quote = False
                quote_char = ""
        elif ch == ":" and not in_quote:
            key = line[:i].strip()
            if not key:
                return None, ""
            value = _strip_inline_comment(line[i + 1:].strip())
            return key, _unquote(value)
    return None, ""


def _strip_inline_comment(value: str) -> str:
    """Remove a trailing ` # comment` that is not inside quotes."""
    in_quote = False
    quote_char = ""
    for i, ch in enumerate(value):
        if ch in ('"', "'"):
            if not in_quote:
                in_quote = True
                quote_char = ch
            elif ch == quote_char:
                in_quote = False
                quote_char = ""
        elif ch == "#" and not in_quote and (i == 0 or value[i - 1].isspace()):
            return value[:i].rstrip()
    return value


def _unquote(value: str) -> str:
    """Strip matching surrounding quotes and unescape `\\"` / `\\\\`."""
    if len(value) >= 2 and value[0] == value[-1] == '"':
        inner = value[1:-1]
        return inner.replace('\\"', '"').replace("\\\\", "\\")
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1]
    return value


def nest_dotted(data: Dict[str, str]) -> Dict[str, Any]:
    """Convert flat {'a.b.c': v} into nested {'a': {'b': {'c': v}}}."""
    root: Dict[str, Any] = {}
    for key, value in data.items():
        parts = key.split(".")
        node = root
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
    return root


# ---------------------------------------------------------------------------
# Portal loading
# ---------------------------------------------------------------------------

def load_portal(name: str, portals_dir: Optional[Path] = None) -> Optional[Dict[str, Any]]:
    """Load portals/<name>.yaml into a nested dict, or None when unknown."""
    name = (name or "").strip()
    if not name:
        return None
    base = Path(portals_dir) if portals_dir else DEFAULT_PORTALS_DIR
    path = base / f"{name}.yaml"
    if not path.is_file():
        return None
    try:
        raw = parse_yaml_flat(path.read_text(encoding="utf-8"))
    except Exception:
        return None
    cfg = nest_dotted(raw)
    if not isinstance(cfg.get("fields"), dict) or cfg.get("portal") != name:
        return None
    return cfg


# ---------------------------------------------------------------------------
# Portal helper loading (issue #45)
# ---------------------------------------------------------------------------

def load_helper(name: str, helpers_dir: Optional[Path] = None):
    """Lazily import helpers/<name>.py as a module, or None when unavailable.

    Returns ``None`` when the helper module does not exist or cannot be loaded
    (e.g. an unknown portal). Portal modules are loaded on demand via
    importlib; importing this package never pulls in portal helpers.
    """
    name = (name or "").strip()
    if not name:
        return None
    base = Path(helpers_dir) if helpers_dir else DEFAULT_HELPERS_DIR
    path = base / f"{name}.py"
    if not path.is_file():
        return None
    try:
        return importlib.import_module(f"helpers.{name}")
    except Exception:
        return None



def portal_fields(cfg: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Return portal fields as [{name, selector, type, source?}] in YAML order."""
    fields = cfg.get("fields")
    if not isinstance(fields, dict):
        return []
    result: List[Dict[str, Any]] = []
    for fname, spec in fields.items():
        if isinstance(spec, dict):
            item: Dict[str, Any] = {"name": fname}
            item.update(spec)
            result.append(item)
    return result


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


def validate_profile(profile: Dict[str, Any], portal_cfg: Dict[str, Any]) -> List[str]:
    """Return the list of missing profile fields required by the portal YAML."""
    missing: List[str] = []
    for field in portal_fields(portal_cfg):
        if field.get("type") == "submit":
            continue  # submit carries no value source
        source = field.get("source")
        if not source:
            continue
        value = profile.get(source)
        if value is None or (isinstance(value, str) and not value.strip()):
            missing.append(source)
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
# Idempotency (#27) — memory record
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


def write_applied_record(memory_dir: Path, job_id: str, profile: Dict[str, Any],
                         portal: str, screenshot_path: Optional[str]) -> Dict[str, Any]:
    """Persist {job_id, contact_email, portal, applied_at, screenshot_path}."""
    record = {
        "job_id": job_id,
        "contact_email": profile.get("email"),
        "portal": portal,
        "applied_at": datetime.now(timezone.utc).isoformat(),
        "screenshot_path": str(screenshot_path) if screenshot_path else None,
        "status": APPLIED_STATUS,
    }
    out_dir = applications_dir(memory_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{job_id}.json"
    out_path.write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


# ---------------------------------------------------------------------------
# Action-plan builder
# ---------------------------------------------------------------------------

def build_action_plan(profile: Dict[str, Any], portal_cfg: Dict[str, Any],
                      job_id: str, job_url: str, confirmed: bool,
                      memory_dir: Path, dry_run: bool,
                      session_check_enabled: bool = True,
                      session_expired: bool = False,
                      login_url: Optional[str] = None,
                      auto_apply: bool = False) -> Dict[str, Any]:
    """Emit the ordered action plan steps (session check / batch fill / screenshot / checkpoint / submit).

    Issue #41 — the session expiry gate: when ``session_check_enabled`` the
    plan starts with a ``verify_session`` step.  When ``session_expired`` the
    plan stops at a confirm_checkpoint carrying the PT-BR login prompt — the
    bot must NOT fill or submit anything; the human has to authenticate first.

    Issue #42 —
      * all form fields (non-submit) are consolidated into a single ``fill_form``
        batch step so the executor makes one browser call instead of several;
      * ``auto_apply`` implies ``confirmed`` (submit is emitted) but omits the
        user ``confirm_checkpoint``, keeping the ``screenshot`` and record steps
        for the audit trail.  Auto-apply never bypasses the (#41) session gate,
        (#28) refusal block, or (#27) idempotency — those are enforced upstream.
    """
    steps: List[Dict[str, Any]] = []
    step_no = 0
    shot_path = str(Path(memory_dir) / SCREENSHOTS_SUBDIR / f"{job_id}.png")

    # --auto-apply implies confirmed: a submit step is emitted.
    confirmed = confirmed or auto_apply

    # Session expiry gate (#41): verify the session before ANY fill.
    if session_check_enabled or session_expired:
        step_no += 1
        steps.append({
            "step": step_no,
            "type": "verify_session",
            "action": "verify_session",
            "url": job_url,
            "expect": "not_auth_page",
            "on_expired": "ask_login_and_confirm",
        })

    # Expired session: never fill/submit. Stop and ask the human to log in.
    if session_expired:
        step_no += 1
        steps.append({
            "step": step_no,
            "type": "confirm_checkpoint",
            "note": SESSION_EXPIRED_DETAIL,
            "login_url": login_url or job_url,
            "screenshot_path": shot_path,
        })
        plan: Dict[str, Any] = {
            "ok": True,
            "portal": portal_cfg.get("portal"),
            "jobId": job_id,
            "jobUrl": job_url,
            "confirmed": confirmed,
            "confirmationRequired": True,
            "dryRun": dry_run,
            "sessionCheck": session_check_enabled,
            "sessionExpired": True,
            "autoApply": auto_apply,
            "steps": steps,
        }
        pattern = portal_cfg.get("form_url_pattern")
        if pattern:
            plan["form_url"] = str(pattern).format(job_id=job_id)
        return plan

    # Issue #42 — consolidate all non-submit fields into ONE fill_form batch step
    # so the executor makes a single browser call (no reload risk between fields).
    fields: List[Dict[str, Any]] = []
    for field in portal_fields(portal_cfg):
        ftype = field.get("type", "")
        if ftype == "submit":
            continue  # submit is appended only after the confirmation gate
        item: Dict[str, Any] = {
            "name": field["name"],
            "selector": field.get("selector", ""),
            "type": ftype,
        }
        source = field.get("source")
        item["value"] = profile.get(source) if source else None
        fields.append(item)

    step_no += 1
    steps.append({
        "step": step_no,
        "type": "fill_form",
        "action": "fill_form",
        "fields": fields,
    })

    # Screenshot: kept in every mode (including auto-apply) for the audit trail.
    step_no += 1
    steps.append({
        "step": step_no,
        "type": "screenshot",
        "path": shot_path,
        "note": "capture the filled form before user confirmation",
    })

    # The user confirm_checkpoint is SKIPPED in auto-apply mode — the bot has
    # explicit authorization to proceed end-to-end without pausing for a manual
    # confirm.  It remains for all interactive (non-auto) plans.
    if not auto_apply:
        step_no += 1
        steps.append({
            "step": step_no,
            "type": "confirm_checkpoint",
            "note": "PAUSE - do not continue until the user confirms every value and the screenshot",
            "screenshot_path": shot_path,
        })

    # Submit is NEVER emitted unless the --confirmed gate is passed (auto-apply
    # implies confirmed, handled above).
    if confirmed:
        submit = next((f for f in portal_fields(portal_cfg) if f.get("type") == "submit"), None)
        step_no += 1
        steps.append({
            "step": step_no,
            "type": "submit",
            "selector": submit.get("selector", "button[type='submit']") if submit else "button[type='submit']",
        })

    plan: Dict[str, Any] = {
        "ok": True,
        "portal": portal_cfg.get("portal"),
        "jobId": job_id,
        "jobUrl": job_url,
        "confirmed": confirmed,
        "confirmationRequired": not confirmed,
        "dryRun": dry_run,
        "sessionCheck": session_check_enabled,
        "sessionExpired": False,
        "autoApply": auto_apply,
        "steps": steps,
    }
    pattern = portal_cfg.get("form_url_pattern")
    if pattern:
        plan["form_url"] = str(pattern).format(job_id=job_id)
    return plan


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


def _guard_detail(block: str, guard: Dict[str, Any]) -> str:
    """Human-readable detail for auth_required / navigation_loop errors."""
    manual = guard.get("manual_url")
    if block == "auth_required":
        return (
            "the current URL is a login/auth/signin page; the bot cannot complete "
            "the application without a session. Open the job manually and finish "
            f"the application by hand: {manual}"
        )
    visits = guard.get("visits")
    if visits is not None:
        return (
            f"the same page has been visited {visits} times (limit {MAX_VISITS}); "
            "this looks like a navigation loop. Open the job manually and finish "
            f"the application by hand: {manual}"
        )
    return (
        "navigation loop detected: same page visited repeatedly. Open the job "
        f"manually and finish the application by hand: {manual}"
    )


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
    parser.add_argument("--memory-dir")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--confirmed", action="store_true")
    parser.add_argument("--record-applied", action="store_true")
    # Issue #42 — auto-apply: implies --confirmed (submit), omits the
    # confirm_checkpoint, keeps screenshot + record for the audit trail.
    parser.add_argument("--auto-apply", action="store_true",
                        help="implied confirmed + skip the confirm checkpoint (never bypasses idempotency/refusal/session safety)")
    # Issue #38 navigation auth/loop guard. Stateless: the bot/executor passes
    # visited history and the current URL; apply.py never tracks state.
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

    if not args.job_url or not args.profile:
        print(json.dumps(build_error(
            "usage",
            "required arguments: --job-url, --profile <json>",
            memory_dir=memory_dir,
        )))
        return 2

    portal_cfg = load_portal(args.portal)
    helper_mod = load_helper(args.portal)
    if portal_cfg is None or helper_mod is None:
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

    missing = validate_profile(profile, portal_cfg)
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

    job_id = derive_job_id(args.job_url)
    if not job_id:
        print(json.dumps(build_error(
            "invalid_job_url",
            f"could not derive a job id from url: {args.job_url}",
            memory_dir=memory_dir,
        )))
        return 1

    # Issue #39 — browser recovery runs BEFORE navigation guard (#38). When the
    # CDP endpoint is unreachable we start Chromium and hand the session back to
    # the human for login. --dry-run never touches the browser.
    if not args.dry_run:
        cdp_url = args.cdp_url or portal_cfg.get("cdp_url") or DEFAULT_CDP_URL
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

    # Issue #41 — session expiry gate. When the browser is on a login/auth page
    # (session expired), the bot must not fill anything: emit a plan that stops
    # at the confirm checkpoint and ask the human to authenticate.  Skipped on
    # --skip-session-check and --dry-run.  Runs BEFORE the #38 navigation guard
    # so the login page is intercepted here instead of surfacing as auth_required.
    session_check_enabled = not (args.skip_session_check or args.dry_run)
    session_expired = False
    session_login_url: Optional[str] = None
    if session_check_enabled:
        session_result = verify_session(
            args.current_url, login_hint_url=args.job_url,
        )
        session_expired = session_result["session"] == "expired"
        session_login_url = session_result.get("login_url")

    if session_expired:
        plan = build_action_plan(
            profile, portal_cfg, job_id, args.job_url,
            confirmed=args.confirmed, memory_dir=memory_dir, dry_run=args.dry_run,
            session_check_enabled=True,
            session_expired=True,
            login_url=session_login_url,
            auto_apply=args.auto_apply,
        )
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0

    # Issue #38 navigation auth/loop guard. When the bot is being redirected to
    # a login page or is stuck re-visiting the same URL, stop early and hand the
    # task back to the human with a direct link + screenshot hint. The guard is
    # stateless: history is supplied via --visited-urls each invocation.
    guard = guard_from_cli(args.job_url, args.current_url, args.visited_urls)
    if guard.get("block") in ("auth_required", "navigation_loop"):
        code = guard["block"]
        detail = _guard_detail(code, guard)
        # Prefer "auth_required" when both conditions apply, matching the
        # acceptance criteria (3rd visit triggers navigation_loop/auth_required).
        print(json.dumps(build_error(
            code,
            detail,
            memory_dir=memory_dir,
            job_id=job_id,
            manual_url=guard.get("manual_url") or args.job_url,
            visited_count=guard.get("visits"),
        )))
        return 1

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

    plan = build_action_plan(
        profile, portal_cfg, job_id, args.job_url,
        confirmed=args.confirmed, memory_dir=memory_dir, dry_run=args.dry_run,
        session_check_enabled=session_check_enabled,
        session_expired=False,
        auto_apply=args.auto_apply,
    )

    # Record the application after a confirmed/auto run, or on explicit
    # --record-applied (bot calls it post-submit). --dry-run never records.
    should_record = (args.record_applied or args.confirmed or args.auto_apply) and not args.dry_run
    if should_record:
        shot = str(Path(memory_dir) / SCREENSHOTS_SUBDIR / f"{job_id}.png")
        record = write_applied_record(
            memory_dir, job_id, profile, portal_cfg.get("portal"), shot
        )
        plan["recorded"] = record

    print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(run())