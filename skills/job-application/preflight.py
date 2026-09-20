#!/usr/bin/env python3
"""
preflight.py — deterministic pre-intent gate for the job-application skill (issue #72).

docs/specs/deterministic-apply-runbook.md §3.1: runs BEFORE any intent is
emitted. Checks, IN ORDER, each returning facts plus a machine-readable error
on failure:

  1. chromium_running      — Chromium for the DEDICATED profile (the
                             user-data-dir) is running; any chromium alone is
                             NOT proof;
  2. cdp_endpoint          — the CDP endpoint from the profile config answers
                             GET <cdp_url>/json;
  3. browser_tool_attached — the configured browser tool is attached to THAT
                             SAME CDP endpoint (tab list matches; a listening
                             port alone is NOT proof);
  4. gupy_session          — the Gupy session is valid (authenticated marker
                             present, and no /candidates/auth redirect).

The bot proceeds only on a full pass; on failure the run stops and reports the
failing check VERBATIM — no improvisation, no fallback browsers, no scripts
(§3.4 fail-closed).

Stdlib only: argparse, json, os, subprocess, urllib, pathlib. No pip
dependencies. The process listing and CDP HTTP fetch dependencies are injected
callables (defaults: pgrep/urlopen) so the unit tests can fake them — the
module itself never talks to a browser.

Usage:
    python3 preflight.py --config <config.json>

Config schema:
    {
      "cdp_url": "http://localhost:9222",            # optional (default)
      "user_data_dir": "~/.chromium-profile-cdp",    # optional (default)
      "browser_tool": {
        "tabs": [{"id": "target-1", "url": "..."}],  # what the tool reports
        "active_page": {"url": "...", "authenticated": true}
      }
    }

Output (stdout, JSON):
    pass   → {"ok": true, "checks": {chromium_running, cdp_endpoint,
             browser_tool_attached, gupy_session}}                (exit 0)
    fail   → {"ok": false, "check": <name>, "error": <code>,
             "detail": <verbatim>, "checks": {<evaluated so far>}} (exit 1)
    usage/config → {"error": "usage" | "invalid_config", ...}      (exit 2)
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, NotRequired, Optional, TypedDict

from navigation import is_auth_url

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_CDP_URL = "http://localhost:9222"
DEFAULT_USER_DATA_DIR = "~/.chromium-profile-cdp"
CDP_TIMEOUT = 2  # seconds for the /json and /json/version probes

# Machine-readable error codes (one per failing check, §3.1).
ERROR_CHROMIUM_NOT_RUNNING = "chromium_not_running"
ERROR_CDP_UNREACHABLE = "cdp_unreachable"
ERROR_BROWSER_TOOL_DETACHED = "browser_tool_detached"
ERROR_GUPY_SESSION_INVALID = "gupy_session_invalid"
ERROR_CONFIG_INVALID = "invalid_config"

CHECK_ORDER = ["chromium_running", "cdp_endpoint",
               "browser_tool_attached", "gupy_session"]


# ---------------------------------------------------------------------------
# Default dependencies (injected in the unit tests)
# ---------------------------------------------------------------------------

def _default_list_procs() -> List[str]:
    """List running process command lines via pgrep (best-effort).

    Empty list on any failure (pgrep missing, no matches, error): the gate is
    fail-closed, so "could not list" behaves exactly like "nothing running".
    """
    try:
        out = subprocess.check_output(
            ["pgrep", "-af", "chrom"], stderr=subprocess.DEVNULL, text=True
        )
    except (subprocess.CalledProcessError, FileNotFoundError, OSError):
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def _default_fetch(url: str, timeout: int = CDP_TIMEOUT):
    """Default CDP HTTP fetch (urllib) with the standard preflight timeout."""
    return urllib.request.urlopen(url, timeout=timeout)


# ---------------------------------------------------------------------------
# Check 1 — chromium_running: process for the DEDICATED profile
# ---------------------------------------------------------------------------

def _is_dedicated_chromium(line: str, expanded_user_data_dir: str) -> bool:
    """True when a process line runs a Chromium binary for the given profile.

    Both signals are required: the chromium/chrome executable appears among the
    first two command tokens (the pid+executable window in ``pgrep -af``
    output), AND the process argv carries a ``--user-data-dir`` that resolves
    to the dedicated profile. The argv value is normalized with ``expanduser``
    BEFORE comparing to the also-expanded expected dir: Chromium is often
    launched with a literal tilde (``--user-data-dir=~/.chromium-profile-cdp``),
    so a raw-substring match of the expanded path against the raw argv would
    false-negative on a perfectly valid process (issue #72 live-run finding).
    """
    tokens = line.split()
    if not tokens:
        return False
    executable = tokens[0]
    window = (os.path.basename(executable),
              tokens[1] if len(tokens) > 1 else "")
    if not any("chrom" in token.lower() for token in window):
        return False
    for i, token in enumerate(tokens):
        if not token.startswith("--user-data-dir"):
            continue
        if "=" in token:
            raw_value = token.split("=", 1)[1]
        elif i + 1 < len(tokens):
            raw_value = tokens[i + 1]  # space-separated flag form
        else:
            raw_value = ""
        profile_dir = os.path.expanduser(raw_value.strip().strip("\"'"))
        if profile_dir == expanded_user_data_dir:
            return True
    return False


def check_chromium_running(user_data_dir: str,
                           list_procs: Optional[Any] = None) -> Dict[str, Any]:
    """Check 1 (§3.1): the Chromium process for the dedicated profile is running.

    ``list_procs`` is an injected zero-arg callable returning process command
    lines (default: :func:`_default_list_procs`). Passes only when a chromium/
    chrome process bound to the EXPANDED *user_data_dir* is found; any other
    chromium instance does not count.

    Returns on pass: ``{"ok": true, "user_data_dir": <expanded>, "process": <line>}``.
    """
    if list_procs is None:
        list_procs = _default_list_procs
    expanded = os.path.expanduser(user_data_dir)
    try:
        procs = list_procs()
    except Exception as exc:
        return {"ok": False, "error": ERROR_CHROMIUM_NOT_RUNNING,
                "detail": f"could not list processes: {exc}"}
    for line in procs or []:
        if _is_dedicated_chromium(line, expanded):
            return {"ok": True, "user_data_dir": expanded, "process": line}
    return {"ok": False, "error": ERROR_CHROMIUM_NOT_RUNNING,
            "detail": f"no Chromium process is running for the dedicated "
                      f"profile '{expanded}'"}


# ---------------------------------------------------------------------------
# Check 2 — cdp_endpoint: the profile-config CDP endpoint answers /json
# ---------------------------------------------------------------------------

def check_cdp_endpoint(cdp_url: str,
                       fetch: Optional[Any] = None) -> Dict[str, Any]:
    """Check 2 (§3.1): the CDP endpoint from the profile config answers /json.

    Performs GET ``<cdp_url>/json`` (spec verbatim — the target-list endpoint,
    not /json/version) and fails closed unless the endpoint returns HTTP 200
    AND a JSON LIST (the CDP target list). A 200 from a random port is NOT
    proof of a CDP endpoint.

    ``fetch`` is an injected ``(url, timeout=..) -> response`` callable
    (default: urllib via :func:`_default_fetch`); the response needs ``status``
    and ``read()``.

    Returns on pass: ``{"ok": true, "targets": <count>, "url": <endpoint>}``.
    """
    if fetch is None:
        fetch = _default_fetch
    endpoint = cdp_url.rstrip("/") + "/json"
    try:
        resp = fetch(endpoint, timeout=CDP_TIMEOUT)
    except Exception as exc:
        return {"ok": False, "error": ERROR_CDP_UNREACHABLE,
                "detail": f"CDP endpoint {endpoint} did not answer /json: {exc}"}
    if resp.status != 200:
        return {"ok": False, "error": ERROR_CDP_UNREACHABLE,
                "detail": f"CDP endpoint {endpoint} answered HTTP {resp.status}"}
    try:
        data = json.loads(resp.read())
    except Exception as exc:
        return {"ok": False, "error": ERROR_CDP_UNREACHABLE,
                "detail": f"CDP endpoint {endpoint} did not return a JSON "
                          f"target list: {exc}"}
    if not isinstance(data, list):
        return {"ok": False, "error": ERROR_CDP_UNREACHABLE,
                "detail": f"CDP endpoint {endpoint} did not return a /json "
                          f"target list"}
    return {"ok": True, "targets": len(data), "url": endpoint}


# ---------------------------------------------------------------------------
# Check 3 — browser_tool_attached: attached to THAT SAME CDP endpoint
# ---------------------------------------------------------------------------

def check_browser_tool_attached(cdp_url: str,
                                tool_tabs: Optional[List[Dict[str, Any]]],
                                fetch: Optional[Any] = None) -> Dict[str, Any]:
    """Check 3 (§3.1): the configured browser tool is attached to the CDP
    endpoint of check 2.

    The page-target ids exposed by GET ``<cdp_url>/json`` must match the tab
    ids the configured browser tool currently reports. A LISTENING PORT ALONE
    IS NOT PROOF: the tool may be attached to a different browser/instance.
    Missing tool, no page targets, empty tabs or any mismatch all fail closed
    (§3.4).

    ``fetch`` is injected as in :func:`check_cdp_endpoint`.

    Returns on pass:
    ``{"ok": true, "matched": <n>, "cdp_page_ids": <set>, "tool_tab_ids": <set>}``.
    """
    if tool_tabs is None:
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": "browser tool is not available; nothing is attached "
                          "to the CDP endpoint (fail-closed)"}
    if fetch is None:
        fetch = _default_fetch
    endpoint = cdp_url.rstrip("/") + "/json"
    try:
        resp = fetch(endpoint, timeout=CDP_TIMEOUT)
    except Exception as exc:
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": f"CDP endpoint {endpoint} is not answering: {exc}"}
    try:
        targets = json.loads(resp.read())
    except Exception:
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": f"CDP endpoint {endpoint} did not return a JSON "
                          f"target list"}
    if not isinstance(targets, list):
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": f"CDP endpoint {endpoint} did not return a /json "
                          f"target list"}

    # Only page targets can be compared against tool tabs (iframes and other
    # target types are transport-internal and never count as tabs).
    cdp_page_ids = {
        str(target["id"])
        for target in targets
        if isinstance(target, dict) and target.get("type") == "page"
        and target.get("id") is not None
    }
    tool_tab_ids = {
        str(tab["id"]) for tab in tool_tabs
        if isinstance(tab, dict) and tab.get("id") is not None
    }

    if not cdp_page_ids:
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": "CDP endpoint reports no page targets; tab "
                          "comparison impossible (fail-closed)"}
    if cdp_page_ids != tool_tab_ids:
        return {"ok": False, "error": ERROR_BROWSER_TOOL_DETACHED,
                "detail": f"browser tool is NOT attached to this CDP endpoint: "
                          f"a listening port alone is not proof "
                          f"(cdp page targets={len(cdp_page_ids)}, "
                          f"tool tabs={len(tool_tab_ids)})"}
    return {"ok": True, "matched": len(cdp_page_ids),
            "cdp_page_ids": cdp_page_ids, "tool_tab_ids": tool_tab_ids}


# ---------------------------------------------------------------------------
# Check 4 — gupy_session: authenticated marker + no auth redirect
# ---------------------------------------------------------------------------

def check_gupy_session(page_state: Dict[str, Any]) -> Dict[str, Any]:
    """Check 4 (§3.1): the Gupy session is valid.

    ``page_state`` is a snapshot of the browser's ACTIVE page as reported by
    the browser tool: ``{"url": str, "authenticated": bool}`` (the
    authenticated marker is observed by the tool, never invented here). Valid
    only when the URL is on a ``gupy.io`` host, is NOT an auth/login page
    (``navigation.is_auth_url`` — e.g. no ``/candidates/auth`` redirect), and
    the authenticated marker is present. Anything else — auth redirect, missing
    marker, non-Gupy host, unparseable URL — fails closed.

    Returns on pass: ``{"ok": true, "url": <url>, "authenticated": true}``.
    """
    if not isinstance(page_state, dict):
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": "active-page snapshot is missing"}
    url = page_state.get("url")
    authenticated = page_state.get("authenticated")
    if not isinstance(url, str) or not url:
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": "active-page snapshot carries no url"}
    try:
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower()
    except ValueError:
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": f"active-page url is not parseable: {url}"}
    if not (host == "gupy.io" or host.endswith(".gupy.io")):
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": f"active page is not on a gupy.io host: {host or url}"}
    if is_auth_url(url):
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": f"active page is an auth/login redirect: {url}"}
    if not authenticated:
        return {"ok": False, "error": ERROR_GUPY_SESSION_INVALID,
                "detail": "no authenticated marker on the Gupy session"}
    return {"ok": True, "url": url, "authenticated": True}


# ---------------------------------------------------------------------------
# Orchestration — run(argv): config load + ordered, short-circuiting checks
# ---------------------------------------------------------------------------

# Issue #72 — machine-readable config CONTRACT validation (stdlib TypedDicts).
# The TypedDicts document the canonical config shape (runbook §3.1); they are
# NOT runtime classes — checks are explicit isinstance/type tests so malformed
# configs refuse with stable codes before any browser check runs.

class BrowserToolContract(TypedDict):
    tabs: List[Any]          # tab objects, each carrying an "id"
    active_page: Dict[str, Any]  # {"url": str, "authenticated": bool}


class PreflightConfigContract(TypedDict, total=False):
    cdp_url: str                         # optional — default applies
    user_data_dir: str                   # optional — default applies
    browser_tool: BrowserToolContract    # required


def validate_config_contract(raw: Any) -> Dict[str, Any]:
    """Validate a decoded preflight config against ``PreflightConfigContract``.

    Returns ``{"ok": True}`` on conformance, or a machine-readable refusal:

        {"ok": False, "error": "invalid_config",
         "problems": [<stable codes, e.g. "browser_tool.missing",
                      "browser_tool.tabs.not_a_list", ...>],
         "detail": <human-readable join of the codes>}

    ``cdp_url`` / ``user_data_dir`` are optional (loader defaults apply);
    ``browser_tool`` with ``tabs`` (a list of tab objects) and ``active_page``
    (an object) is required — mirrors the runbook §3.1 config schema.
    """
    problems: List[str] = []
    if not isinstance(raw, dict):
        return {"ok": False, "error": "invalid_config",
                "problems": ["config.not_an_object"],
                "detail": "config must be a JSON object"}

    for key in ("cdp_url", "user_data_dir"):
        if key in raw and not isinstance(raw[key], str):
            problems.append(f"{key}.not_a_string")

    browser_tool = raw.get("browser_tool")
    if "browser_tool" not in raw:
        problems.append("browser_tool.missing")
    elif not isinstance(browser_tool, dict):
        problems.append("browser_tool.not_an_object")
    else:
        tabs = browser_tool.get("tabs")
        if "tabs" not in browser_tool:
            problems.append("browser_tool.tabs.missing")
        elif not isinstance(tabs, list):
            problems.append("browser_tool.tabs.not_a_list")
        else:
            for item in tabs:
                if not isinstance(item, dict):
                    problems.append("browser_tool.tabs.item_not_an_object")
        active_page = browser_tool.get("active_page")
        if "active_page" not in browser_tool:
            problems.append("browser_tool.active_page.missing")
        elif not isinstance(active_page, dict):
            problems.append("browser_tool.active_page.not_an_object")

    if problems:
        return {"ok": False, "error": "invalid_config",
                "problems": problems, "detail": "; ".join(problems)}
    return {"ok": True}


def _load_config(config_path: Path):
    """Load and normalize the preflight config; (config, None) or (None, err).

    ``config`` is normalized to
    ``{"cdp_url", "user_data_dir", "tabs", "active_page"}`` with the documented
    defaults applied for the optional keys. The shape is validated by
    :func:`validate_config_contract` (issue #72): malformed configs refuse
    with stable machine-readable codes underneath the standard
    ``error == ERROR_CONFIG_INVALID`` surface (exit 2).
    """
    if not config_path.is_file():
        return None, {"error": ERROR_CONFIG_INVALID,
                      "detail": f"config file not found: {config_path}"}
    try:
        raw = json.loads(config_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return None, {"error": ERROR_CONFIG_INVALID,
                      "detail": f"config is not valid JSON: {exc}"}
    validation = validate_config_contract(raw)
    if not validation.get("ok"):
        return None, {"error": ERROR_CONFIG_INVALID,
                      "detail": validation.get("detail")}
    browser_tool = raw["browser_tool"]
    return {
        "cdp_url": raw.get("cdp_url") or DEFAULT_CDP_URL,
        "user_data_dir": raw.get("user_data_dir") or DEFAULT_USER_DATA_DIR,
        "tabs": browser_tool["tabs"],
        "active_page": browser_tool["active_page"],
    }, None


def _jsonable(value: Any) -> Any:
    """Recursively convert check-fact sets into JSON-safe sorted lists."""
    if isinstance(value, set):
        return sorted(value)
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    return value


def _print_failure(check_name: str, result: Dict[str, Any],
                   checks: Dict[str, Any]) -> int:
    """Print the machine-readable failure (check reported VERBATIM) and return 1."""
    payload = {
        "ok": False,
        "check": check_name,
        "error": result.get("error"),
        "detail": result.get("detail"),
        "checks": _jsonable(checks),
    }
    print(json.dumps(payload, ensure_ascii=False))
    return 1


def parse_args(argv: Optional[List[str]] = None):
    """Parse CLI flags with argparse (raises SystemExit on invalid flags)."""
    parser = argparse.ArgumentParser(prog="preflight.py", add_help=False)
    parser.add_argument("--config", default=None,
                        help="path to the preflight config JSON "
                             "(cdp_url, user_data_dir, browser_tool)")
    return parser.parse_args(argv)


def run(argv: Optional[List[str]] = None) -> int:
    """CLI entry point; prints JSON to stdout and returns the exit code.

    * 0 — every check passed (full-pass gate).
    * 1 — the first failing check, reported VERBATIM (no improvisation).
    * 2 — usage/config errors (missing --config, unreadable/invalid config).
    """
    try:
        args = parse_args(argv)
    except SystemExit:
        # argparse already wrote the flag error to stderr; keep stdout JSON-only.
        print(json.dumps({"error": "usage", "detail": "invalid arguments"}))
        return 2

    if not args.config:
        print(json.dumps({"error": "usage",
                          "detail": "required argument: --config <json>"}))
        return 2

    config, config_err = _load_config(Path(args.config))
    if config_err is not None:
        print(json.dumps(config_err, ensure_ascii=False))
        return 2

    # Checks run strictly in the §3.1 order; the first failure stops the run.
    checks: Dict[str, Any] = {}

    chromium = check_chromium_running(user_data_dir=config["user_data_dir"])
    checks["chromium_running"] = chromium
    if not chromium.get("ok"):
        return _print_failure("chromium_running", chromium, checks)

    cdp = check_cdp_endpoint(cdp_url=config["cdp_url"])
    checks["cdp_endpoint"] = cdp
    if not cdp.get("ok"):
        return _print_failure("cdp_endpoint", cdp, checks)

    tool = check_browser_tool_attached(cdp_url=config["cdp_url"],
                                       tool_tabs=config["tabs"])
    checks["browser_tool_attached"] = tool
    if not tool.get("ok"):
        return _print_failure("browser_tool_attached", tool, checks)

    session = check_gupy_session(page_state=config["active_page"])
    checks["gupy_session"] = session
    if not session.get("ok"):
        return _print_failure("gupy_session", session, checks)

    print(json.dumps({"ok": True, "checks": _jsonable(checks)},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(run())