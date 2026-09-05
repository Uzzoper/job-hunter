#!/usr/bin/env python3
"""
apply.py — structured job-portal application planner for the Hermes bot (issue #37).

Plans a Gupy application as an ordered ACTION PLAN (JSON) that the bot executes
through its browser tool. This script NEVER touches a browser: it validates
inputs, enforces guardrails (#27 idempotency, #28 refusal), and emits steps
with CSS selectors loaded from portals/<portal>.yaml.

Stdlib only: argparse, json, re, urllib.parse, pathlib. No pip dependencies.
Portal YAML files stay flat (`key: value` / dotted keys), so a small built-in
subset parser is used instead of a yaml library.

Usage:
    python3 apply.py --job-url <url> --profile <profile.json> --portal gupy \\
        [--memory-dir <dir>] [--dry-run] [--confirmed] [--record-applied]

Output: JSON to stdout (action plan or {"error": <code>, "detail": ...}).
"""

import argparse
import json
import re
import sys
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_PORTALS_DIR = Path(__file__).resolve().parent / "portals"
DEFAULT_MEMORY_DIR = (
    Path.home() / ".hermes" / "profiles" / "jobhunter-bot" / "memails"
)
APPLICATIONS_SUBDIR = "applications"
SCREENSHOTS_SUBDIR = "screenshots"
APPLIED_STATUS = "applied"
REFUSAL_MARKER = "NO_APPLY"

# Gupy job URLs look like https://<portal>.gupy.io/jobs/<id-slug>
JOB_SLUG_RE = re.compile(r"/jobs/([^/?#]+)")

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
                      memory_dir: Path, dry_run: bool) -> Dict[str, Any]:
    """Emit the ordered action plan steps (fill/upload/screenshot/checkpoint/submit)."""
    steps: List[Dict[str, Any]] = []
    step_no = 0

    for field in portal_fields(portal_cfg):
        ftype = field.get("type", "")
        if ftype == "submit":
            continue  # submit is appended only after the confirmation gate
        step_no += 1
        step: Dict[str, Any] = {
            "step": step_no,
            "type": ftype,
            "field": field["name"],
            "selector": field.get("selector", ""),
        }
        source = field.get("source")
        step["value"] = profile.get(source) if source else None
        steps.append(step)

    # Screenshot + explicit confirmation checkpoint.
    shot_path = str(Path(memory_dir) / SCREENSHOTS_SUBDIR / f"{job_id}.png")
    step_no += 1
    steps.append({
        "step": step_no,
        "type": "screenshot",
        "path": shot_path,
        "note": "capture the filled form before user confirmation",
    })

    step_no += 1
    steps.append({
        "step": step_no,
        "type": "confirm_checkpoint",
        "note": "PAUSE - do not continue until the user confirms every value and the screenshot",
        "screenshot_path": shot_path,
    })

    # Submit is NEVER emitted unless the explicit --confirmed gate is passed.
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


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv: Optional[List[str]]):
    """Parse CLI flags with argparse (raises SystemExit on invalid flags)."""
    parser = argparse.ArgumentParser(prog="apply.py", add_help=False)
    parser.add_argument("--job-url")
    parser.add_argument("--profile")
    parser.add_argument("--portal")
    parser.add_argument("--memory-dir")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--confirmed", action="store_true")
    parser.add_argument("--record-applied", action="store_true")
    return parser.parse_args(argv)


def run(argv: Optional[List[str]] = None) -> int:
    """CLI entry point; returns the process exit code."""
    try:
        args = parse_args(argv)
    except SystemExit:
        # argparse already wrote the flag error to stderr; keep stdout JSON-only.
        print(json.dumps(build_error("usage", "invalid arguments")))
        return 2

    memory_dir = Path(args.memory_dir) if args.memory_dir else DEFAULT_MEMORY_DIR

    if not args.job_url or not args.profile or not args.portal:
        print(json.dumps(build_error(
            "usage",
            "required arguments: --job-url, --profile <json>, --portal <name>",
            memory_dir=memory_dir,
        )))
        return 2

    portal_cfg = load_portal(args.portal)
    if portal_cfg is None:
        print(json.dumps(build_error(
            "unknown_portal",
            f"portal '{args.portal}' is not supported (v1 supports: gupy)",
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
    )

    # Record the application after a confirmed run, or on explicit
    # --record-applied (bot calls it post-submit). --dry-run never records.
    should_record = (args.record_applied or args.confirmed) and not args.dry_run
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