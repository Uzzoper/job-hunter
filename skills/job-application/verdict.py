#!/usr/bin/env python3
"""
verdict.py — the verdict stage that splits planned vs applied (docs/specs/mcp-apply-loop.md, phase 2).

The executor apply loop (observe -> classify -> act -> verify) ends by handing
its outcome to this module. verdict.py is the SINGLE POINT OF TRUTH for what
gets persisted:

  * evaluate_submit()     — pure evidence evaluation. Decides whether a final
                            submit counts as SUBMIT_OK (success text OR success
                            URL segment, AND a post-submit screenshot).
  * decide()              — outcome -> record routing. Only SUBMIT_OK *with
                            evidence* and *with confirmation* writes
                            applications/<job_id>.json and reports
                            record_applied=True (the executor then calls
                            job_api.api_record_applied). Every other outcome
                            (INCOMPLETE, auth_required, confirm_declined,
                            SUBMIT_DONE_NO_EVIDENCE, error) writes only
                            attempts/<attempt_id>/<ts>.json and never blocks a
                            future retry.
  * write_applied_record() — writes the exact applied record (base schema +
                            verdict + evidence), called only for SUBMIT_OK.
  * write_attempt_log()    — writes the per-attempt diagnostic trace for EVERY
                            run, whatever the outcome.

Design: stdlib-only and pure. This module NEVER touches the browser or the
network — the backend call (job_api.api_record_applied) is injected as a
`backend` callable by the executor and invoked here (so the verdict stage
remains the only caller of the applied backend record, per the spec).

Record paths under the bot memory dir (default
~/.hermes/profiles/jobhunter-bot/memails):
    applications/<job_id>.json                 (only on SUBMIT_OK)
    attempts/<attempt_id>/<ts>.json            (every run)
"""

import json
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SUBMIT_OK = "SUBMIT_OK"
SUBMIT_DONE_NO_EVIDENCE = "SUBMIT_DONE_NO_EVIDENCE"
INCOMPLETE = "INCOMPLETE"
AUTH_REQUIRED = "auth_required"
CONFIRM_DECLINED = "confirm_declined"
ERROR_OUTCOME = "error"

APPLIED_STATUS = "applied"
APPLICATIONS_SUBDIR = "applications"
ATTEMPTS_SUBDIR = "attempts"

# Issue / spec: SUCCESS TEXT evidence (case-insensitive substring on the
# flattened AX snapshot) is satisfied when ANY phrase matches.
SUCCESS_PHRASES = (
    "Inscrição realizada",
    "Candidatura enviada",
    "Candidatura realizada",
    "Você se candidatou",
    "Aplicação enviada",
    "Application submitted",
    "You have applied",
)

# ISSUE: SUCCESS URL evidence — a lowercase path segment of the final URL.
SUCCESS_URL_SEGMENTS = frozenset(
    {"sucesso", "success", "confirmacao", "obrigado", "applied"}
)

# Default detail strings per non-verified outcome.
_DEFAULT_REASONS = {
    SUBMIT_DONE_NO_EVIDENCE: "missing_success_evidence",
    CONFIRM_DECLINED: "missing_confirmation",
    AUTH_REQUIRED: "stop_on_auth_url",
    INCOMPLETE: "unresolved",
    ERROR_OUTCOME: "executor_error",
}


# ---------------------------------------------------------------------------
# Pure evidence evaluation
# ---------------------------------------------------------------------------

def _collect_text(nodes: Any) -> str:
    """Recursively collect every string value from the AX node structure."""
    texts: List[str] = []

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            for value in node.values():
                if isinstance(value, str):
                    texts.append(value)
                else:
                    visit(value)
        elif isinstance(node, list):
            for item in node:
                visit(item)

    visit(nodes)
    return "\n".join(texts).lower()


def _matching_success_phrase(page_text: str) -> Optional[str]:
    """Return the first success phrase found (case-insensitive), or None."""
    for phrase in SUCCESS_PHRASES:
        if phrase.lower() in page_text:
            return phrase
    return None


def _matching_success_segment(final_url: str) -> Optional[str]:
    """Return the first success path segment of the URL, or None."""
    segments = urllib.parse.urlparse(final_url).path.lower().split("/")
    for segment in segments:
        if segment and segment in SUCCESS_URL_SEGMENTS:
            return segment
    return None


def evaluate_submit(final_url: Optional[str],
                    ax_nodes: Optional[List[Dict[str, Any]]],
                    screenshot_path: Optional[str]) -> Dict[str, Any]:
    """Evaluate the post-apply-final page and decide SUBMIT_OK vs no evidence.

    Pure — no browser, no filesystem, no network. Returns:
        {"verdict": "SUBMIT_OK" | "SUBMIT_DONE_NO_EVIDENCE",
         "evidence": {method, match, final_url, screenshot} | None,
         "detail": ...}
    """
    evidence: Optional[Dict[str, Any]] = None
    detail = "no success signal"

    page_text = _collect_text(ax_nodes or [])
    phrase = _matching_success_phrase(page_text)
    segment = _matching_success_segment(final_url or "") if final_url else None

    if phrase is not None:
        method, match = "success_text", phrase
    elif segment is not None:
        method, match = "success_url", segment
    else:
        method, match = None, None

    if method is None:
        detail = "no success text and no success URL segment"
    elif not screenshot_path:
        detail = f"success signal found ({method}) but no post-submit screenshot"
    else:
        evidence = {
            "method": method,
            "match": match,
            "final_url": str(final_url),
            "screenshot": str(screenshot_path),
        }

    return {
        "verdict": SUBMIT_OK if evidence is not None else SUBMIT_DONE_NO_EVIDENCE,
        "evidence": evidence,
        "detail": detail,
    }


# ---------------------------------------------------------------------------
# File writers (stdlib filesystem only)
# ---------------------------------------------------------------------------

def _applications_dir(memory_dir: Path) -> Path:
    return Path(memory_dir) / APPLICATIONS_SUBDIR


def _attempts_dir(memory_dir: Path, attempt_id: str) -> Path:
    return Path(memory_dir) / ATTEMPTS_SUBDIR / attempt_id


def _file_safe_timestamp(ts: str) -> str:
    """Reduce an ISO-8601 timestamp to a file-safe, deterministic name."""
    safe = "".join(ch for ch in ts if ch.isalnum() or ch in ("-", "_", "."))
    return safe or "unknown"


def write_applied_record(memory_dir: Path, job_id: str, *,
                         portal: str,
                         contact_email: Optional[str],
                         applied_at: str,
                         screenshot_path: Optional[str],
                         verdict: str,
                         evidence: Dict[str, Any],
                         backend_record: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Persist the official applied record with evidence + verdict fields.

    Called ONLY on SUBMIT_OK (verified evidence + confirmation). applied_at is
    the verdict (post-submit) timestamp, never the plan time.
    """
    record: Dict[str, Any] = {
        "job_id": job_id,
        "contact_email": contact_email,
        "portal": portal,
        "applied_at": applied_at,
        "screenshot_path": str(screenshot_path) if screenshot_path else None,
        "status": APPLIED_STATUS,
        "verdict": verdict,
        "evidence": evidence,
    }
    if backend_record is not None:
        record["backend_record"] = backend_record

    out_dir = _applications_dir(memory_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{job_id}.json"
    out_path.write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


def write_attempt_log(memory_dir: Path, *,
                      attempt_id: str,
                      job_id: str,
                      job_url: str,
                      portal: str,
                      started_at: str,
                      ended_at: str,
                      outcome: str,
                      reason: Optional[str] = None,
                      trace: Optional[List[Dict[str, Any]]] = None,
                      final_page: Optional[Dict[str, Any]] = None,
                      screenshot_path: Optional[str] = None,
                      manual_url: Optional[str] = None,
                      verdict_block: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Persist attempts/<attempt_id>/<ts>.json — written for EVERY run.

    Keeps the diagnostics and loop trace. A missing entry anywhere in the flow
    must never block a future retry; only applications/<job_id>.json does that.
    """
    record: Dict[str, Any] = {
        "attempt_id": attempt_id,
        "job_id": job_id,
        "job_url": job_url,
        "portal": portal,
        "started_at": started_at,
        "ended_at": ended_at,
        "outcome": outcome,
        "reason": reason,
    }
    if final_page is not None:
        record["final_page"] = final_page
    if trace is not None:
        record["trace"] = trace
    if screenshot_path is not None:
        record["screenshot_path"] = str(screenshot_path)
    if manual_url is not None:
        record["manual_url"] = str(manual_url)
    record["verdict"] = verdict_block or {
        "submitted": False, "evidence": None, "detail": reason or "not submitted",
    }

    out_dir = _attempts_dir(memory_dir, attempt_id)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{_file_safe_timestamp(ended_at)}.json"
    out_path.write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


# ---------------------------------------------------------------------------
# Outcome -> record routing (the single point of truth)
# ---------------------------------------------------------------------------

def _invoke_backend(backend: Callable[[int], Any],
                    backend_job_id: int) -> Dict[str, Any]:
    """Best-effort backend applied record (spec business rule 9).

    Failure NEVER fails the local verdict — it is reported as
    {"ok": False, "error": ...} and carries on.
    """
    try:
        response = backend(backend_job_id)
    except Exception as exc:  # defensive: never crash the record path
        response = {"error": "api_error", "detail": f"{type(exc).__name__}: {exc}"}
    if isinstance(response, dict) and "error" in response:
        return {"ok": False, "error": response["error"]}
    if isinstance(response, dict):
        return {"ok": True, **response}
    return {"ok": True, "error": "unexpected response"}


def decide(memory_dir: Path, *,
           outcome: str,
           reason: Optional[str] = None,
           job_id: str,
           attempt_id: str,
           job_url: str,
           portal: str,
           contact_email: Optional[str] = None,
           started_at: Optional[str] = None,
           ended_at: Optional[str] = None,
           trace: Optional[List[Dict[str, Any]]] = None,
           final_page: Optional[Dict[str, Any]] = None,
           final_url: Optional[str] = None,
           ax_nodes: Optional[List[Dict[str, Any]]] = None,
           screenshot_path: Optional[str] = None,
           manual_url: Optional[str] = None,
           confirmed: bool = True,
           backend: Optional[Callable[[int], Any]] = None,
           backend_job_id: Optional[int] = None) -> Dict[str, Any]:
    """Route an executor outcome to the correct on-disk records.

    Returns a routing summary:
        outcome, reason, written_applied, written_attempt, applied_record,
        attempt_record, evidence, record_applied, backend_record

    Rules enforced here (docs/specs/mcp-apply-loop.md, business rules 1, 3, 4, 6):
      * applications/<job_id>.json is written ONLY for SUBMIT_OK (verified
        evidence) AND confirmed.
      * A SUBMIT_OK without success evidence degrades to
        SUBMIT_DONE_NO_EVIDENCE (attempt only, retryable).
      * A final submit without the confirmed flag degrades to confirm_declined
        (attempt only) — the confirmation gate blocks recording.
      * auth_required is never recorded as applied, even when confirmed (the
        never-fill / stop-on-auth policy is a hard gate).
    """
    now_iso = datetime.now(timezone.utc).isoformat()
    started_at = started_at or now_iso
    ended_at = ended_at or now_iso

    results: Dict[str, Any] = {
        "outcome": outcome,
        "reason": reason,
        "written_applied": False,
        "written_attempt": True,
        "applied_record": None,
        "attempt_record": None,
        "evidence": None,
        "record_applied": False,
        "backend_record": None,
    }

    # Confirmation gate — no final submit without --confirmed / --auto-apply.
    if outcome == SUBMIT_OK and not confirmed:
        outcome = CONFIRM_DECLINED
        results["outcome"] = outcome
        if reason is None:
            reason = _DEFAULT_REASONS[CONFIRM_DECLINED]
    if reason is None:
        reason = _DEFAULT_REASONS.get(outcome, "unresolved")

    evidence: Optional[Dict[str, Any]] = None

    # Evidence evaluation only happens for a claimed, confirmed submit.
    if outcome == SUBMIT_OK:
        evaluation = evaluate_submit(final_url, ax_nodes, screenshot_path)
        results["evidence"] = evaluation["evidence"]
        if evaluation["verdict"] != SUBMIT_OK:
            outcome = evaluation["verdict"]  # SUBMIT_DONE_NO_EVIDENCE
            results["outcome"] = outcome
            if reason is None:
                reason = _DEFAULT_REASONS[SUBMIT_DONE_NO_EVIDENCE]
            evidence = None
        else:
            evidence = evaluation["evidence"]
            reason = "verified_submit"

    if outcome == SUBMIT_OK and evidence is not None:
        # Best-effort backend record first, then persist locally regardless.
        backend_record = None
        if backend is not None and backend_job_id is not None:
            backend_record = _invoke_backend(backend, backend_job_id)
            results["backend_record"] = backend_record
        applied_record = write_applied_record(
            memory_dir, job_id,
            portal=portal,
            contact_email=contact_email,
            applied_at=ended_at,
            screenshot_path=screenshot_path,
            verdict=SUBMIT_OK,
            evidence=evidence,
            backend_record=backend_record,
        )
        results["written_applied"] = True
        results["applied_record"] = applied_record
        results["record_applied"] = True

    verdict_block = {
        "submitted": outcome == SUBMIT_OK,
        "evidence": results["evidence"],
        "detail": reason,
    }

    results["reason"] = reason
    results["attempt_record"] = write_attempt_log(
        memory_dir,
        attempt_id=attempt_id,
        job_id=job_id,
        job_url=job_url,
        portal=portal,
        started_at=started_at,
        ended_at=ended_at,
        outcome=outcome,
        reason=reason,
        trace=trace,
        final_page=final_page,
        screenshot_path=screenshot_path,
        manual_url=manual_url,
        verdict_block=verdict_block,
    )
    return results


if __name__ == "__main__":
    import sys
    sys.exit("verdict.py is a library module — run verdict_test.py instead.")