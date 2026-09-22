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
from typing import Any, Callable, Dict, List, NotRequired, Optional, TypedDict

from apply import is_corrupt_gupy_slug  # issue #82 — shared slug integrity guard

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
    "Inscrição concluída",
    "Candidatura enviada",
    "Candidatura realizada",
    "Candidatura finalizada",
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

# The complete set of outcomes the verdict stage may persist in an attempt
# record. Anything else read back from disk is refused as malformed.
KNOWN_OUTCOMES = frozenset({
    SUBMIT_OK, SUBMIT_DONE_NO_EVIDENCE, INCOMPLETE,
    AUTH_REQUIRED, CONFIRM_DECLINED, ERROR_OUTCOME,
})


# ---------------------------------------------------------------------------
# Machine-readable record CONTRACT validation (issue #72 — stdlib TypedDicts)
# ---------------------------------------------------------------------------
#
# verdict.py is the SINGLE writer of the two persisted record kinds; this pure
# validator lets the bot REFUSE a malformed record with stable, machine-readable
# codes instead of reading back garbage for idempotency (#27) or review. The
# TypedDicts document the canonical shapes the writers produce. They are NOT
# runtime classes — every check is an explicit isinstance/type test so JSON
# round-trip corruption (str-backend_id, missing evidence, made-up outcome) is
# caught deterministically.

class AppliedRecordContract(TypedDict):
    job_id: str
    contact_email: NotRequired[Optional[str]]
    portal: str
    applied_at: str
    screenshot_path: NotRequired[Optional[str]]
    status: str
    verdict: str
    evidence: NotRequired[Dict[str, Any]]
    backend_record: NotRequired[Dict[str, Any]]
    backend_job_id: NotRequired[Optional[int]]


class AttemptRecordContract(TypedDict):
    attempt_id: str
    job_id: str
    job_url: str
    portal: str
    started_at: str
    ended_at: str
    outcome: str
    reason: NotRequired[Optional[str]]
    final_page: NotRequired[Dict[str, Any]]
    trace: NotRequired[List[Dict[str, Any]]]
    screenshot_path: NotRequired[Optional[str]]
    manual_url: NotRequired[Optional[str]]
    verdict: NotRequired[Dict[str, Any]]


def _record_str(record: Dict[str, Any], namespace: str, field: str,
                problems: List[str], allow_none: bool = False) -> None:
    """Append ``<path>.missing`` / ``<path>.not_a_string`` for a string field
    (``namespace`` may be empty; ``allow_none`` tolerates explicit nulls)."""
    path = field if not namespace else f"{namespace}.{field}"
    if field not in record:
        problems.append(f"{path}.missing")
    else:
        value = record[field]
        if value is None and allow_none:
            return
        if not isinstance(value, str):
            problems.append(f"{path}.not_a_string")


def _validate_applied_record(record: Dict[str, Any],
                             problems: List[str]) -> None:
    """Applied records: written ONLY on SUBMIT_OK with evidence (spec rules)."""
    for field in ("job_id", "portal", "applied_at"):
        _record_str(record, "", field, problems)
    # Issue #82 — a '.' (hence '...') is impossible in a genuine slug; a
    # corrupt job_id must never reach disk (it would poison idempotency and
    # the consistency audit — the #82 phantom-divergence root cause).
    if isinstance(record.get("job_id"), str) and \
            is_corrupt_gupy_slug(record["job_id"]):
        problems.append("job_id.corrupt_gupy_slug")
    for field in ("contact_email", "screenshot_path"):
        _record_str(record, "", field, problems, allow_none=True)

    if "status" not in record:
        problems.append("status.missing")
    elif record["status"] != APPLIED_STATUS:
        problems.append("status.not_an_applied_status")
    if "verdict" not in record:
        problems.append("verdict.missing")
    elif record["verdict"] != SUBMIT_OK:
        problems.append("verdict.not_submit_ok")

    for field in ("evidence", "backend_record"):
        if field in record and not isinstance(record[field], dict):
            problems.append(f"{field}.not_an_object")
    if "backend_job_id" in record:
        value = record["backend_job_id"]
        if value is not None and \
                (isinstance(value, bool) or not isinstance(value, int)):
            problems.append("backend_job_id.not_an_int")


def _validate_attempt_record(record: Dict[str, Any],
                             problems: List[str]) -> None:
    """Attempt records: the per-run diagnostic trace, whatever the outcome."""
    for field in ("attempt_id", "job_id", "job_url", "portal",
                  "started_at", "ended_at"):
        _record_str(record, "", field, problems)
    # Issue #82 — see _validate_applied_record: the same corrupt-key guard.
    if isinstance(record.get("job_id"), str) and \
            is_corrupt_gupy_slug(record["job_id"]):
        problems.append("job_id.corrupt_gupy_slug")
    _record_str(record, "", "reason", problems, allow_none=True)

    if "outcome" not in record:
        problems.append("outcome.missing")
    elif record["outcome"] not in KNOWN_OUTCOMES:
        problems.append("outcome.unknown")

    for field in ("final_page", "verdict"):
        if field in record and not isinstance(record[field], dict):
            problems.append(f"{field}.not_an_object")
    for field in ("screenshot_path", "manual_url"):
        if field in record and record[field] is not None and \
                not isinstance(record[field], str):
            problems.append(f"{field}.not_a_string")

    if "trace" in record:
        if not isinstance(record["trace"], list):
            problems.append("trace.not_a_list")
        else:
            for item in record["trace"]:
                if not isinstance(item, dict):
                    problems.append("trace.item_not_an_object")


def validate_record_contract(record: Any, kind: str) -> Dict[str, Any]:
    """Validate a persisted record against its typed contract (issue #72).

    ``kind`` is ``"applied"`` (applications/<job_id>.json) or ``"attempt"``
    (attempts/<attempt_id>/<ts>.json). Returns ``{"ok": True}`` on
    conformance, or a machine-readable refusal:

        {"ok": False, "error": "invalid_record_contract", "kind": <kind>,
         "problems": [<stable codes, e.g. "outcome.unknown",
                      "status.not_an_applied_status", ...>],
         "detail": <human-readable join of the codes>}

    Unknown kinds are refused with ``record.kind.unknown``. Pure and
    read-only — never touches the filesystem.
    """
    problems: List[str] = []
    if not isinstance(record, dict):
        problems.append("record.not_an_object")
    elif kind == "applied":
        _validate_applied_record(record, problems)
    elif kind == "attempt":
        _validate_attempt_record(record, problems)
    else:
        problems.append("record.kind.unknown")

    if problems:
        return {"ok": False, "error": "invalid_record_contract", "kind": kind,
                "problems": problems, "detail": "; ".join(problems)}
    return {"ok": True}


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
                         backend_record: Optional[Dict[str, Any]] = None,
                         backend_job_id: Optional[int] = None) -> Dict[str, Any]:
    """Persist the official applied record with evidence + verdict fields.

    Called ONLY on SUBMIT_OK (verified evidence + confirmation). applied_at is
    the verdict (post-submit) timestamp, never the plan time.

    Idempotency-key chain hardening: the FILE key stays the portal slug
    ({job_id}.json — numeric and base64 slugs coexist); the numeric backend id,
    when known, is embedded in the record BODY (backend_job_id) so a slug-miss
    can reconcile against the backend id — the backend numeric id never reaches
    a filename. The key is omitted entirely when backend_job_id is None.
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
    if backend_job_id is not None:
        record["backend_job_id"] = backend_job_id

    # PR #80 review P1 — validate BEFORE persisting: a record that violates the
    # contract returns the refusal dict and writes NOTHING (a malformed file
    # would poison idempotency/review later). The caller must surface it.
    contract = validate_record_contract(record, "applied")
    if contract.get("ok") is not True:
        return contract

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

    # PR #80 review P1 — validate BEFORE persisting (see write_applied_record).
    contract = validate_record_contract(record, "attempt")
    if contract.get("ok") is not True:
        return contract

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
            backend_job_id=backend_job_id,
        )
        if isinstance(applied_record, dict) \
                and applied_record.get("ok") is False:
            # PR #80 review P1 — the writer REFUSED (invalid contract): nothing
            # landed on disk; surface the refusal and keep the flags truthful.
            results["applied_record"] = applied_record
            results["written_applied"] = False
            results["record_applied"] = False
        else:
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
    if isinstance(results["attempt_record"], dict) \
            and results["attempt_record"].get("ok") is False:
        # PR #80 review P1 — the writer REFUSED (invalid contract): report it.
        results["written_attempt"] = False
    return results


if __name__ == "__main__":
    import sys
    sys.exit("verdict.py is a library module — run verdict_test.py instead.")