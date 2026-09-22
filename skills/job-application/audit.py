#!/usr/bin/env python3
"""
audit.py — read-only consistency audit of applied-job state (issue #81).

docs/specs/consistency-audit.md: before a batch, cross-checks the FOUR sources
of applied-job state and lists every mismatch, so the bot (or operator) sees
inconsistencies BEFORE starting a new apply batch:

  1. backend jobs.lifecycleState   — the funnel source of truth, exposed by
     GET /api/jobs as `lifecycleState` (X-Bot-Token, issue #47);
  2. applications/<job_id>.json    — verdict.py applied records (status
     "applied", written ONLY on verified SUBMIT_OK);
  3. attempts/**/*.json            — the per-run attempt/quarantine trail
     (nested attempts/<attempt_id>/<ts>.json AND legacy flat attempts/*.json);
  4. <screenshots-dir>/<job_id>.png — the post-submit evidence convention
     (resolved via the conventional name AND each record's screenshot_path).

Checks (issue #81 "Expected checks"):
  * applications status=applied → backend must be SUBMITTED, a matching
    attempt must be a success, a screenshot must resolve;
  * backend SUBMITTED → a matching applications/ file must exist;
  * attempts referencing job ids unknown to the backend → flagged once per key;
  * screenshots with no application and no attempt → flagged as orphans;
  * quarantine (non-verified) attempt records are expected state: annotated
    [quarantine] on the divergence line they contribute to, NEVER flagged alone.

Design: stdlib-only and READ-ONLY — this module never writes, moves, renames
or deletes anything. Pure + I/O split, same style as preflight.py / verdict.py:
the backend fetch is injectable (`fetch` callable, default urllib) so the unit
tests never touch the network; `check()` is a pure function. Identity between
portal slugs and backend jobs follows apply.derive_job_id (the same key the
record writers use); the numeric backend id is accepted as a key too.

Exit codes: 0 clean, 1 divergences (or api/token errors), 2 usage. Output is
one DIVERGENCE line per finding plus "SUMMARY: <n> divergence(s) ...",
machine-greppable for a future pre-batch gate.

Usage:
    python3 audit.py [--api-base-url <url>] [--api-token <token>]
                     [--profile-dir <dir>] [--memory-dir <dir>]
                     [--screenshots-dir <dir>]
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

from apply import derive_job_id   # identity: portal slug derivation (shared key)
import job_api                     # token resolution + service-token semantics

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_API_BASE_URL = "http://localhost:8080"
DEFAULT_MEMORY_DIR = (
    Path.home() / ".hermes" / "profiles" / "jobhunter-bot" / "memails"
)
APPLICATIONS_SUBDIR = "applications"
ATTEMPTS_SUBDIR = "attempts"
SCREENSHOTS_SUBDIR = "screenshots"
APPLIED_STATUS = "applied"
SUBMITTED_LIFECYCLE = "SUBMITTED"

_SUBMIT_OK_OUTCOME = "SUBMIT_OK"
_HTTP_TIMEOUT = 10  # seconds for the single GET /api/jobs round-trip


# ---------------------------------------------------------------------------
# Identity mapping: portal slug ↔ backend job (id + url-derived slug)
# ---------------------------------------------------------------------------

def backend_job_keys(job: Dict[str, Any]) -> set:
    """The local-record keys a backend job answers to.

    Returns ``{str(id)}`` (when the numeric id is not None) ∪
    ``{apply.derive_job_id(job.url)}`` — the SAME derivation the record
    writers use (verdict.py / apply.py), so ``609`` matches both a numeric
    backend id and a Gupy URL ``/jobs/609``. ``None``/unresolvable forms are
    dropped; a job never answers to the bare string "None".
    """
    keys: set = set()
    job_id = job.get("id")
    if job_id is not None:
        keys.add(str(job_id))
    url = job.get("url")
    if isinstance(url, str):
        slug = derive_job_id(url)
        if slug:
            keys.add(slug)
    return keys


# ---------------------------------------------------------------------------
# Source loaders — READ-ONLY; corrupt/missing files degrade to missing
# ---------------------------------------------------------------------------

def _safe_load_json(path: Path) -> Optional[Dict[str, Any]]:
    """Decode a JSON record, ``None`` on unreadable/corrupt input.

    A broken record must degrade exactly like a missing one (the divergence
    surface stays deterministic — never a traceback).
    """
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None
    return data if isinstance(data, dict) else None


def load_applications(memory_dir: Path) -> Dict[str, Dict[str, Any]]:
    """``applications/<job_id>.json`` → ``job_id -> record body`` (read-only)."""
    result: Dict[str, Dict[str, Any]] = {}
    apps_dir = Path(memory_dir) / APPLICATIONS_SUBDIR
    if not apps_dir.is_dir():
        return result
    for path in sorted(apps_dir.glob("*.json")):
        record = _safe_load_json(path)
        if record is None:
            continue
        job_id = record.get("job_id")
        if isinstance(job_id, str) and job_id:
            result[job_id] = record
    return result


def load_attempts(memory_dir: Path) -> Dict[str, List[Dict[str, Any]]]:
    """``attempts/**/*.json`` (flat + ``attempts/<attempt_id>/<ts>.json``)
    → ``job_id -> [record bodies]`` (read-only)."""
    result: Dict[str, List[Dict[str, Any]]] = {}
    attempts_root = Path(memory_dir) / ATTEMPTS_SUBDIR
    if not attempts_root.is_dir():
        return result
    for path in sorted(attempts_root.glob("**/*.json")):
        record = _safe_load_json(path)
        if record is None:
            continue
        job_id = record.get("job_id")
        if not isinstance(job_id, str) or not job_id:
            continue
        result.setdefault(job_id, []).append(record)
    return result


def load_screenshots(screenshots_dir: Path) -> List[Path]:
    """``<screenshots-dir>/*.png`` sorted by filename (read-only)."""
    directory = Path(screenshots_dir)
    if not directory.is_dir():
        return []
    return sorted(p for p in directory.glob("*.png") if p.is_file())


# ---------------------------------------------------------------------------
# Pure check — the whole cross-check against a decoded backend list
# ---------------------------------------------------------------------------

def _has_success_attempt(records: List[Dict[str, Any]]) -> bool:
    """True when any attempt record carries a success verdict.

    Accepts either the canonical outcome (``SUBMIT_OK``) or
    ``verdict.submitted is True`` (the verdict block every attempt carries).
    """
    for record in records:
        if not isinstance(record, dict):
            continue
        if record.get("outcome") == _SUBMIT_OK_OUTCOME:
            return True
        verdict = record.get("verdict")
        if isinstance(verdict, dict) and verdict.get("submitted") is True:
            return True
    return False


def _attempt_summary(records: List[Dict[str, Any]]) -> str:
    """``attempt_id:outcome`` list for a job (the quarantine annotation)."""
    parts = []
    for record in records:
        if not isinstance(record, dict):
            continue
        attempt_id = record.get("attempt_id") or "?"
        outcome = record.get("outcome") or "?"
        parts.append(f"{attempt_id}:{outcome}")
    return ",".join(parts)


def _has_screenshot(record: Dict[str, Any], job_id: str,
                    screenshots_dir: Path,
                    screenshot_paths: List[Path]) -> bool:
    """True when a screenshot resolves for the applied record.

    Checks, in order (issue #81 path ambiguity — SKILL.md's conventional
    ``<job_id>.png`` vs mcp-apply-loop's timestamped ``<job_id>-<ts>.png``
    recorded in ``screenshot_path``):
      1. ``<screenshots_dir>/<job_id>.png`` exists;
      2. the record's ``screenshot_path`` points at an existing file;
      3. the record's ``screenshot_path`` basename exists under the
         screenshots dir (covers relative/foreign-root conventions).
    """
    conventional = Path(screenshots_dir) / f"{job_id}.png"
    if conventional.is_file() or conventional in screenshot_paths:
        return True
    raw_path = record.get("screenshot_path")
    if not isinstance(raw_path, str) or not raw_path:
        return False
    recorded = Path(raw_path)
    if recorded.is_file():
        return True
    resolved = Path(screenshots_dir) / recorded.name
    return resolved.is_file() or resolved in screenshot_paths


def _referenced_screenshot_names(applications: Dict[str, Dict[str, Any]],
                                 attempts: Dict[str, List[Dict[str, Any]]]) -> set:
    """Basenames of every ``screenshot_path`` a record references."""
    referenced: set = set()
    for record in applications.values():
        if isinstance(record, dict) and isinstance(record.get("screenshot_path"), str):
            referenced.add(Path(record["screenshot_path"]).name)
    for records in attempts.values():
        for record in records:
            if isinstance(record, dict) and isinstance(record.get("screenshot_path"), str):
                referenced.add(Path(record["screenshot_path"]).name)
    return referenced


def check(backend_jobs: List[Dict[str, Any]],
          memory_dir: Path,
          screenshots_dir: Path) -> List[str]:
    """Run the full cross-check; return one readable divergence line per finding.

    Pure: no HTTP, no stdout, no writes. ``backend_jobs`` is the decoded
    ``GET /api/jobs`` list (each dict: id, url, lifecycleState). An empty
    return means the four sources are consistent (exit 0 contract).
    """
    applications = load_applications(memory_dir)
    attempts = load_attempts(memory_dir)
    screenshots = load_screenshots(screenshots_dir)

    # Backend key → lifecycleState (a numeric id and its url-slug key both map).
    known_keys: set = set()
    backend_lifecycle: Dict[str, Any] = {}
    for job in backend_jobs:
        if not isinstance(job, dict):
            continue
        keys = backend_job_keys(job)
        known_keys.update(keys)
        for key in keys:
            backend_lifecycle.setdefault(key, job.get("lifecycleState"))

    lines: List[str] = []

    # Check 1 — applications status=applied → backend SUBMITTED, a successful
    # attempt, a resolvable screenshot (the three job-609 divergences).
    for job_id, record in sorted(applications.items()):
        if not isinstance(record, dict) or record.get("status") != APPLIED_STATUS:
            continue
        lifecycle = backend_lifecycle.get(job_id)
        if lifecycle != SUBMITTED_LIFECYCLE:
            lines.append(
                f"DIVERGENCE applied_without_backend: job={job_id} "
                f"applications=applied backend_lifecycle={lifecycle}"
            )
        job_attempts = attempts.get(job_id, [])
        if not _has_success_attempt(job_attempts):
            # Quarantine annotation: the failed attempts are EXPECTED state and
            # are listed here, never flagged as a separate divergence.
            lines.append(
                f"DIVERGENCE applied_without_successful_attempt: job={job_id} "
                f"applications=applied attempts={_attempt_summary(job_attempts)} "
                f"[quarantine]"
            )
        if not _has_screenshot(record, job_id, screenshots_dir, screenshots):
            lines.append(
                f"DIVERGENCE applied_without_screenshot: job={job_id} "
                f"applications=applied screenshots=none"
            )

    # Check 2 — backend SUBMITTED → matching applications/ file must exist.
    for key in sorted(k for k, state in backend_lifecycle.items()
                      if state == SUBMITTED_LIFECYCLE):
        if key not in applications:
            lines.append(
                f"DIVERGENCE backend_submitted_without_application: job={key} "
                f"backend=SUBMITTED applications=missing"
            )

    # Check 3 — attempts referencing keys unknown to the backend: ONE line per
    # unknown key (never per attempt file), grouping the attempt ids.
    for job_id, records in sorted(attempts.items()):
        if job_id in known_keys:
            continue
        attempt_ids = ",".join(
            str(r.get("attempt_id") or "?")
            for r in records if isinstance(r, dict)
        )
        lines.append(
            f"DIVERGENCE attempt_unknown_job: job={job_id} "
            f"attempts={attempt_ids} backend=unknown"
        )

    # Check 4 — orphan screenshots: referenced by no record AND not named after
    # a job that has ANY local record (screenshot_path field or <job_id>.png).
    local_job_ids = set(applications) | set(attempts)
    referenced = _referenced_screenshot_names(applications, attempts)
    for shot in screenshots:
        if shot.name in referenced:
            continue
        stem = shot.name[:-4] if shot.name.lower().endswith(".png") else shot.name
        if stem in local_job_ids:
            continue
        lines.append(
            f"DIVERGENCE orphan_screenshot: screenshot={shot.name} "
            f"applications=none attempts=none"
        )

    return lines


# ---------------------------------------------------------------------------
# Backend fetch (injectable — default urllib, service-token §issue #47)
# ---------------------------------------------------------------------------

def _backend_request(url: str, token: str,
                     fetch: Optional[Any] = None, timeout: int = _HTTP_TIMEOUT) -> Any:
    """GET an authenticated backend URL; decode JSON or map to an error dict.

    Exactly the job_api._request_json semantics save for the injected
    ``fetch`` callable (default: ``urllib.request.urlopen``, receiving the
    urllib Request built here — the same shape the tests can fake). Both
    failure transports are covered: urllib raises ``HTTPError`` for HTTP
    failures, while an injected fake may simply return a response with a
    ``status`` attribute — a non-2xx status maps the same way:
       * 401 → {"error": "unauthorized"}
       * 404 → {"error": "not_found"}
       * other → {"error": "api_error", "detail": ...}
    """
    if fetch is None:
        fetch = urllib.request.urlopen
    headers = {
        "X-Bot-Token": token,
        "Accept": "application/json",
    }
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with fetch(req, timeout=timeout) as resp:
            status = int(getattr(resp, "status", 200) or 200)
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, b""
    except Exception as exc:  # transport-level errors keep stdout JSON-only
        return {"error": "api_error", "detail": f"{type(exc).__name__}: {exc}"}
    if status >= 400:
        if status == 401:
            return {"error": "unauthorized"}
        if status == 404:
            return {"error": "not_found"}
        return {"error": "api_error", "detail": f"HTTP {status} from {url}"}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {"error": "api_error", "detail": "invalid JSON response"}


def fetch_backend_jobs(base_url: str, token: str,
                       fetch: Optional[Any] = None) -> Union[List[Any], Dict[str, Any]]:
    """GET ``<base_url>/api/jobs`` — the funnel source (list endpoint)."""
    url = base_url.rstrip("/") + "/api/jobs"
    payload = _backend_request(url, token, fetch=fetch)
    if isinstance(payload, dict) and "error" in payload:
        return payload
    if not isinstance(payload, list):
        return {"error": "api_error",
                "detail": "unexpected response: expected a job list"}
    return payload


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv: Optional[List[str]] = None):
    """Parse CLI flags with argparse (raises SystemExit on invalid flags)."""
    parser = argparse.ArgumentParser(prog="audit.py", add_help=False)
    parser.add_argument("--api-base-url", default=DEFAULT_API_BASE_URL,
                        help=f"Job Hunter API base URL (default: {DEFAULT_API_BASE_URL})")
    parser.add_argument("--api-token", default=None,
                        help="Job Hunter API service token (overrides JOBHUNTER_API_TOKEN env and <profile-dir>/api-token.txt)")
    parser.add_argument("--profile-dir", default=None,
                        help="bot profile dir holding api-token.txt (default ~/.hermes/profiles/jobhunter-bot)")
    parser.add_argument("--memory-dir", default=None,
                        help=f"bot memory dir holding applications/ + attempts/ + screenshots/ (default: {DEFAULT_MEMORY_DIR})")
    parser.add_argument("--screenshots-dir", default=None,
                        help="screenshots dir override (default: <memory-dir>/screenshots; the issue-#81 profile-root location can be passed here)")
    return parser.parse_args(argv)


def _distinct_jobs(lines: List[str]) -> int:
    """Count distinct `job=` keys across the divergence lines."""
    jobs: set = set()
    for line in lines:
        for token in line.split():
            if token.startswith("job="):
                jobs.add(token)
    return len(jobs)


def run(argv: Optional[List[str]] = None,
        fetch: Optional[Any] = None) -> int:
    """CLI entry point; prints the audit report and returns the exit code.

    * 0 — no divergences (all four sources consistent).
    * 1 — divergences found, or a clean JSON api/token error.
    * 2 — usage errors.

    ``fetch`` is injected for the tests exactly like preflight.run() exposes
    ``list_procs`` / ``fetch``; the default is a real urllib round-trip.
    """
    try:
        args = parse_args(argv)
    except SystemExit:
        # argparse already wrote the flag error to stderr; keep stdout clean.
        print(json.dumps({"error": "usage", "detail": "invalid arguments"}))
        return 2

    memory_dir = Path(args.memory_dir) if args.memory_dir else DEFAULT_MEMORY_DIR
    screenshots_dir = Path(args.screenshots_dir) if args.screenshots_dir \
        else Path(memory_dir) / SCREENSHOTS_SUBDIR

    token = job_api.resolve_token(args.api_token, profile_dir=args.profile_dir)
    if isinstance(token, dict):
        print(json.dumps(token, ensure_ascii=False))
        return 1

    base_url = args.api_base_url or DEFAULT_API_BASE_URL
    jobs = fetch_backend_jobs(base_url, token, fetch=fetch)
    if isinstance(jobs, dict) and "error" in jobs:
        print(json.dumps(jobs, ensure_ascii=False))
        return 1

    lines = check(jobs, memory_dir, screenshots_dir)
    for line in lines:
        print(line)
    if lines:
        print(f"SUMMARY: {len(lines)} divergence(s) across "
              f"{_distinct_jobs(lines)} job(s)")
        return 1
    print("SUMMARY: 0 divergences (all sources consistent)")
    return 0


if __name__ == "__main__":
    sys.exit(run())