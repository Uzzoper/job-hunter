#!/usr/bin/env python3
"""
verdict_test.py — mcp-apply-loop (docs/specs/mcp-apply-loop.md) phase 2 tests
for verdict.py (the verdict stage that splits planned vs applied).

Plain unittest (pytest-compatible), no browser and no MCP involved: the
executor loop is not unit-tested here. A temp directory stands in for the bot
memory dir, and a fake backend callable stands in for job_api.api_record_applied
(the verdict module must never touch the network itself).

Spec test plan (verdict_test.py rows 1-8):
  * record-only-on-verified-evidence  — SUBMIT_OK + evidence writes the applied
    record; no evidence → no applications/<job_id>.json
  * incomplete-never-blocks-retry     — INCOMPLETE / auth_required / budget /
    loop_stalled write only the attempt log and applications/ stays empty
  * auth-stop                         — auth outcome never records applied, even
    when confirmed; manual_url + screenshot kept in the attempt log
  * confirm-gate                      — a final submit without the confirmed flag
    is never recorded as applied
  * applied record carries the exact evidence/verdict shape and applied_at is
    the verdict (post-submit) timestamp

Run:
    python3 verdict_test.py
    python3 -m pytest verdict_test.py
"""

import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verdict  # noqa: E402  (RED phase: module does not exist yet)

# Reuse apply's idempotency pre-flight to prove retryability (kept unchanged).
import apply  # noqa: E402


JOB_ID = "dev-backend-jr-8472"
JOB_URL = "https://jobs.gupy.io/jobs/8472"
ATTEMPT_ID = "7c2a1f4e-9b6d-4c3a-8f1e-2d5a9b0c71e3"
PORTAL = "gupy"
CONTACT_EMAIL = "juan@example.com"
STARTED_AT = "2026-09-10T14:19:02.000+00:00"
ENDED_AT = "2026-09-10T14:22:01.000+00:00"
SCREENSHOT = str(Path("screenshots") / f"{JOB_ID}-20260910T1422.png")


EVIDENCE_URL = "https://jobs.gupy.io/jobs/8472"


def success_evidence(**overrides):
    """Build the evidence dict evaluate_submit produces for SUBMIT_OK."""
    evidence = {
        "method": "success_text",
        "match": "Inscrição realizada",
        "final_url": EVIDENCE_URL,
        "screenshot": SCREENSHOT,
    }
    evidence.update(overrides)
    return evidence


def make_attempt(**overrides):
    """Base kwargs for verdict.decide(); individual tests override."""
    kwargs = {
        "memory_dir": None,  # set per-test
        "outcome": verdict.SUBMIT_OK,
        "job_id": JOB_ID,
        "attempt_id": ATTEMPT_ID,
        "job_url": JOB_URL,
        "portal": PORTAL,
        "contact_email": CONTACT_EMAIL,
        "started_at": STARTED_AT,
        "ended_at": ENDED_AT,
        "final_url": "https://jobs.gupy.io/jobs/8472",
        "ax_nodes": [{"role": "heading", "name": "Inscrição realizada"}],
        "screenshot_path": SCREENSHOT,
        "confirmed": True,
    }
    kwargs.update(overrides)
    return kwargs


class FakeBackend:
    """Stand-in for job_api.api_record_applied — records the calls made."""

    def __init__(self):
        self.calls = []

    def __call__(self, backend_job_id):
        self.calls.append(backend_job_id)
        return {"jobId": backend_job_id, "status": "applied"}


class VerifyEvidenceTests(unittest.TestCase):
    """Pure evaluate_submit() — what counts as SUBMIT_OK evidence."""

    def test_success_text_matching_is_case_insensitive(self):
        ax = [{"role": "heading", "name": "Inscrição REALIZADA com sucesso"}]
        result = verdict.evaluate_submit(
            "https://jobs.gupy.io/jobs/8472", ax, SCREENSHOT
        )
        self.assertEqual(result["verdict"], verdict.SUBMIT_OK)
        self.assertEqual(result["evidence"]["method"], "success_text")

    def test_success_url_segment_sufficient(self):
        result = verdict.evaluate_submit(
            "https://jobs.gupy.io/candidaturas/confirmacao/8472",
            [],
            SCREENSHOT,
        )
        self.assertEqual(result["verdict"], verdict.SUBMIT_OK)
        self.assertEqual(result["evidence"]["method"], "success_url")
        self.assertEqual(result["evidence"]["match"], "confirmacao")

    def test_missing_screenshot_is_no_evidence(self):
        ax = [{"role": "heading", "name": "Inscrição realizada"}]
        result = verdict.evaluate_submit("https://jobs.gupy.io/jobs/8472", ax, None)
        self.assertEqual(result["verdict"], verdict.SUBMIT_DONE_NO_EVIDENCE)
        self.assertIsNone(result["evidence"])


class VerdictRoutingTests(unittest.TestCase):
    """decide() — outcome -> record routing is the single point of truth."""

    def setUp(self):
        self._tmp = tempfile.mkdtemp(prefix="verdict_test_")
        self.memory_dir = Path(self._tmp)
        self.applied_path = (
            self.memory_dir / "applications" / f"{JOB_ID}.json"
        )
        self.attempts_dir = self.memory_dir / "attempts" / ATTEMPT_ID
        self.backend = FakeBackend()

    def tearDown(self):
        shutil.rmtree(self._tmp, ignore_errors=True)

    def _single_attempt_file(self):
        files = list(self.attempts_dir.glob("*.json"))
        self.assertEqual(len(files), 1, "expected exactly one attempt file")
        return files[0]

    # Test 1 — record-only-on-verified-evidence
    def test_record_only_on_verified_evidence(self):
        result = verdict.decide(
            **make_attempt(memory_dir=self.memory_dir, backend=self.backend,
                           backend_job_id=7)
        )
        self.assertTrue(self.applied_path.is_file())
        self.assertEqual(result["written_applied"], True)
        self.assertEqual(result["record_applied"], True)
        self.assertEqual(self.backend.calls, [7])
        # The attempt (final trace) is also written for SUBMIT_OK.
        self.assertTrue(self._single_attempt_file().is_file())

    def test_record_only_on_verified_evidence_when_bare_submit_ok(self):
        # A bare SUBMIT_OK outcome without success evidence must NOT record.
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.SUBMIT_OK,
                final_url="https://jobs.gupy.io/jobs/8472",
                ax_nodes=[{"role": "main", "name": "Página da vaga"}],
                backend=self.backend,
                backend_job_id=7,
            )
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(result["written_applied"], False)
        self.assertEqual(result["record_applied"], False)
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(result["outcome"], verdict.SUBMIT_DONE_NO_EVIDENCE)

    # Test 2 — submit-done-no-evidence-not-recorded
    def test_submit_done_no_evidence_not_recorded(self):
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.SUBMIT_DONE_NO_EVIDENCE,
                screenshot_path="screenshots/post-submit.png",
                backend=self.backend,
                backend_job_id=7,
            )
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(self.backend.calls, [])
        attempt = json.loads(self._single_attempt_file().read_text(encoding="utf-8"))
        # The post-submit screenshot is kept for human review.
        self.assertEqual(attempt["screenshot_path"], "screenshots/post-submit.png")
        self.assertFalse(attempt["verdict"]["submitted"])

    # Test 3 — incomplete-never-blocks-retry
    def test_incomplete_never_blocks_retry(self):
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.INCOMPLETE,
                reason="budget_exceeded",
                final_url="https://jobs.gupy.io/jobs/8472/triagem/3",
                final_page={"url": "https://jobs.gupy.io/jobs/8472/triagem/3",
                            "page": "triagem",
                            "signals": ["radio", "select", "next_button"]},
                trace=[{"seq": 1, "page": "start", "action": "next", "ok": True},
                       {"seq": 2, "page": "form", "action": "fill", "ok": True},
                       {"seq": 3, "page": "triagem", "action": "fill", "ok": True}],
            )
        )
        self.assertEqual(result["outcome"], verdict.INCOMPLETE)
        self.assertEqual(result["reason"], "budget_exceeded")
        self.assertFalse(self.applied_path.exists())
        attempt = json.loads(self._single_attempt_file().read_text(encoding="utf-8"))
        self.assertEqual(attempt["reason"], "budget_exceeded")
        self.assertEqual(len(attempt["trace"]), 3)
        # Retry is possible: the already_applied pre-flight still finds nothing.
        self.assertIsNone(apply.check_idempotency(self.memory_dir, JOB_ID))

    # Test 4 — auth-stop-never-fills-credentials
    def test_auth_stop_never_fills_credentials(self):
        trace = [
            {"seq": 1, "page": "start", "action": "next", "ok": True},
            {"seq": 2, "page": "auth", "action": "observe",
             "ok": True, "blocked_by": "stop_on_auth_url"},
        ]
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.AUTH_REQUIRED,
                confirmed=True,  # confirmation NEVER overrides the auth stop
                manual_url=JOB_URL,
                screenshot_path="screenshots/auth-20260910T1420.png",
                trace=trace,
            )
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(result["record_applied"], False)
        # The fill/upload actions are absent from the trace (never executed).
        actions = [step["action"] for step in trace]
        self.assertNotIn("fill", actions)
        self.assertNotIn("upload", actions)
        attempt = json.loads(self._single_attempt_file().read_text(encoding="utf-8"))
        self.assertEqual(attempt["outcome"], verdict.AUTH_REQUIRED)
        self.assertEqual(attempt["manual_url"], JOB_URL)
        self.assertEqual(attempt["screenshot_path"], "screenshots/auth-20260910T1420.png")

    # Test 5 — confirm-gate-blocks-final-submit
    def test_confirm_gate_blocks_final_submit(self):
        result = verdict.decide(
            **make_attempt(memory_dir=self.memory_dir, confirmed=False)
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(result["record_applied"], False)
        self.assertEqual(result["outcome"], verdict.CONFIRM_DECLINED)
        self.assertEqual(result["reason"], "missing_confirmation")
        attempt = json.loads(self._single_attempt_file().read_text(encoding="utf-8"))
        self.assertEqual(attempt["outcome"], verdict.CONFIRM_DECLINED)

    def test_confirm_gate_registered_submit_writes_applied(self):
        # Contrast: the same evidence WITH confirmation writes the record, and
        # a later already_applied pre-flight would now short-circuit.
        result = verdict.decide(
            **make_attempt(memory_dir=self.memory_dir, confirmed=True)
        )
        self.assertTrue(self.applied_path.is_file())
        self.assertEqual(result["written_applied"], True)
        preflight = apply.check_idempotency(self.memory_dir, JOB_ID)
        self.assertIsNotNone(preflight)
        self.assertEqual(preflight["status"], "applied")

    # Test 6 — loop-stalled-writes-attempt-only
    def test_loop_stalled_writes_attempt_only(self):
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.INCOMPLETE,
                reason="loop_stalled",
                final_url="https://jobs.gupy.io/jobs/8472/triagem/2",
            )
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(result["reason"], "loop_stalled")
        self.assertTrue(self._single_attempt_file().is_file())
        self.assertIsNone(apply.check_idempotency(self.memory_dir, JOB_ID))

    # Test 7 — budget-exceeded-writes-attempt-only
    def test_budget_exceeded_writes_attempt_only(self):
        result = verdict.decide(
            **make_attempt(
                memory_dir=self.memory_dir,
                outcome=verdict.INCOMPLETE,
                reason="budget_exceeded",
                final_url="https://jobs.gupy.io/jobs/8472/form",
            )
        )
        self.assertFalse(self.applied_path.exists())
        self.assertEqual(result["reason"], "budget_exceeded")
        attempt = json.loads(self._single_attempt_file().read_text(encoding="utf-8"))
        self.assertFalse(attempt["verdict"]["submitted"])
        self.assertIsNone(apply.check_idempotency(self.memory_dir, JOB_ID))

    # Test 8 — applied-record-format-and-evidence
    def test_applied_record_format_and_evidence(self):
        result = verdict.decide(
            **make_attempt(memory_dir=self.memory_dir, confirmed=True)
        )
        self.assertTrue(self.applied_path.is_file())
        record = json.loads(self.applied_path.read_text(encoding="utf-8"))
        self.assertEqual(
            set(record.keys()),
            {"job_id", "contact_email", "portal", "applied_at",
             "screenshot_path", "status", "verdict", "evidence"},
        )
        self.assertEqual(record["job_id"], JOB_ID)
        self.assertEqual(record["contact_email"], CONTACT_EMAIL)
        self.assertEqual(record["portal"], PORTAL)
        self.assertEqual(record["screenshot_path"], SCREENSHOT)
        self.assertEqual(record["status"], "applied")
        self.assertEqual(record["verdict"], verdict.SUBMIT_OK)
        self.assertEqual(record["evidence"], success_evidence())
        # applied_at is the verdict (post-submit) timestamp, NOT the plan time.
        self.assertEqual(record["applied_at"], ENDED_AT)
        self.assertEqual(result["applied_record"]["applied_at"], ENDED_AT)


if __name__ == "__main__":
    unittest.main()