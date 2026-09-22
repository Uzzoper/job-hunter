#!/usr/bin/env python3
"""
audit_test.py — issue #81 tests for audit.py (read-only consistency audit).

Spec: docs/specs/consistency-audit.md. audit.py cross-checks the four sources
of applied-job state BEFORE a batch:

  1. backend jobs.lifecycleState (GET /api/jobs, X-Bot-Token)
  2. <memory-dir>/applications/<job_id>.json   (verdict.py applied records)
  3. <memory-dir>/attempts/**/*.json           (per-run attempt / quarantine trail)
  4. <screenshots-dir>/<job_id>.png            (post-submit evidence convention)

The audit is strictly READ-ONLY and prints one line per divergence plus a
summary; exit 0 clean, exit 1 divergences, exit 2 usage.

Plain unittest (pytest-compatible), no Spring, no network: a temp dir stands
in for the bot memory dir and the backend list is served by an injected fetch
fake exactly like the existing suites (preflight_test.py, job_api_test.py).
Every scenario rides the real CLI (`audit.run(...)` with the injected fetch)
so the exit codes AND the line output are battle-tested — the check-level
functions are also covered directly.

Contract the GREEN module must implement (documented here so the RED run fails
only on the missing import and nothing else is ambiguous):

  Constants:
    DEFAULT_API_BASE_URL   = "http://localhost:8080"
    DEFAULT_MEMORY_DIR     = Path.home()/".hermes"/"profiles"/"jobhunter-bot"/"memails"
    APPLIED_STATUS         = "applied"
    SUBMITTED_LIFECYCLE    = "SUBMITTED"

  Identity mapping:
    backend_job_keys(job: dict) -> set[str]
        {str(job["id"])} (when id is not None) ∪ {apply.derive_job_id(job["url"])}
        (both forms normalized; None/empty derivations dropped)

  Source loaders (read-only, corrupt files degrade to missing):
    load_applications(memory_dir: Path) -> dict[str, dict]
        applications/<job_id>.json  →  job_id -> record body
    load_attempts(memory_dir: Path) -> dict[str, list[dict]]
        attempts/**/*.json (flat + nested) → job_id -> [record bodies]
    load_screenshots(screenshots_dir: Path) -> list[Path]
        *.png sorted by filename

  Pure check (no HTTP, no stdout):
    check(backend_jobs: list[dict], memory_dir: Path, screenshots_dir: Path)
        -> list[str]  (one readable divergence line per finding; [] == clean)

  Backend fetch + orchestration:
    fetch_backend_jobs(base_url: str, token: str, fetch=None)
        -> list[dict] | error dict (reuses job_api._request_json semantics:
           401 -> {"error": "unauthorized"}, 404 -> {"error": "not_found"},
           other/transport -> {"error": "api_error", ...})
    run(argv: Optional[list[str]] = None, fetch=None) -> int
        0 clean, 1 divergences / api / missing_token, 2 usage; prints
        divergence lines + "SUMMARY: ..." to stdout (JSON only for errors)

Divergence line format (one per divergence, greppable):
    DIVERGENCE <code>: <job=KEY sourceA=sA sourceB=sB ...>
    ──────────────────
    applied_without_backend                → backend_lifecycle=None|VALUE
    applied_without_successful_attempt     → attempts=<aids...> outcomes=<...> [quarantine]
    applied_without_screenshot             → screenshots=none
    backend_submitted_without_application  → applications=missing
    attempt_unknown_job                    → backend=unknown (ONE line per key)
    orphan_screenshot                      → applications=none attempts=none

Quarantine rule: a non-verified attempt record is expected state; it is
annotated on the divergence lines it contributes to but never flagged alone.

Run:
    python3 audit_test.py
    python3 -m pytest audit_test.py
"""

import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
import unittest.mock
from contextlib import redirect_stdout
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import audit  # noqa: E402  (RED phase: module does not exist yet)

import apply  # noqa: E402  — derive_job_id is part of the identity mapping


# ---------------------------------------------------------------------------
# Fixture helpers — tmp memory dirs, never the real ~/.hermes
# ---------------------------------------------------------------------------

def write_json(path: Path, payload: dict) -> Path:
    """Write a JSON fixture file (created inside a tmp test dir)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def backend_job(job_id: int, url: str, lifecycle=None) -> dict:
    """One backend JobResponse fixture (only the fields the audit reads)."""
    job = {"id": job_id, "url": url, "lifecycleState": lifecycle}
    return job


def applied_record(job_id: str, screenshot_path=None) -> dict:
    """The verdict.py applied-record body fixture (status=applied)."""
    return {
        "job_id": job_id,
        "contact_email": "bot@example.com",
        "portal": "gupy",
        "applied_at": "2026-09-10T14:22:00+00:00",
        "screenshot_path": screenshot_path,
        "status": "applied",
        "verdict": "SUBMIT_OK",
        "evidence": {"method": "success_text", "match": "Inscrição realizada"},
    }


def attempt_record(job_id: str, attempt_id: str, outcome: str,
                   reason=None) -> dict:
    """The verdict.py attempt-record body fixture."""
    record = {
        "attempt_id": attempt_id,
        "job_id": job_id,
        "job_url": f"https://jobs.gupy.io/jobs/{job_id}",
        "portal": "gupy",
        "started_at": "2026-09-10T14:10:00+00:00",
        "ended_at": "2026-09-10T14:22:00+00:00",
        "outcome": outcome,
        "reason": reason,
    }
    record["verdict"] = {"submitted": outcome == "SUBMIT_OK",
                         "evidence": None,
                         "detail": reason or "fixture"}
    return record


def snapshot_tree(root: Path) -> dict:
    """Full read-only fingerprint of a fixture tree: relative path -> sha256."""
    fingerprint = {}
    for path in sorted(root.rglob("*")):
        if path.is_file():
            fingerprint[str(path.relative_to(root))] = hashlib.sha256(
                path.read_bytes()).hexdigest()
    return fingerprint


class FakeHttpResponse:
    """Minimal urllib response stand-in: status + read() bytes (context
    manager like the real urllib response)."""

    def __init__(self, status, payload):
        self.status = status
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def read(self):
        return self._payload


def fake_fetch(payload, status=200):
    """Return a fetch fake serving ``payload`` as the GET /api/jobs list.

    Mirrors urllib: the callable receives a urllib Request object (the audit
    builds it with the X-Bot-Token header) and must return a response with
    ``status`` and ``read()``.
    """

    def fetch(req, timeout=10):
        return FakeHttpResponse(status, json.dumps(payload).encode("utf-8"))

    return fetch


def run_cli(argv, fetch):
    """Run audit.run(argv, fetch=fetch) in-process; return (code, stdout)."""
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = audit.run(argv, fetch=fetch)
    return code, buf.getvalue()


CONSISTENT_URL_A = "https://jobs.gupy.io/jobs/609"
CONSISTENT_URL_B = "https://jobs.gupy.io/jobs/812"


# ---------------------------------------------------------------------------
# Scenario fixtures — the real incidents encoded as tmp trees
# ---------------------------------------------------------------------------

def build_scenario_609_orphan(tmp: Path):
    """Incident (a): job 609 orphan.

        applications/609.json      → status=applied
        attempts/<aid1>/<ts>.json  → outcome=SESSION_EXPIRED (quarantine)
        backend 609                → lifecycleState=null
        screenshots/609.png        → MISSING
    """
    mem = tmp / "mem"
    write_json(mem / "applications" / "609.json", applied_record("609"))
    write_json(
        mem / "attempts" / "attempt-609-1" / "20260910T1415.json",
        attempt_record("609", "attempt-609-1", "SESSION_EXPIRED",
                       reason="session expired on submit"),
    )
    backend = [backend_job(609, CONSISTENT_URL_A, lifecycle=None)]
    return mem, backend


def build_scenario_backend_submitted_no_app(tmp: Path):
    """Incident (b): backend SUBMITTED but no applications file.

        backend 812    → lifecycleState=SUBMITTED
        applications/  → EMPTY (missing 812.json)
        attempts/      → one SUBMIT_OK attempt (so the lifecycle has a trail)
        screenshots    → 812.png present
    """
    mem = tmp / "mem"
    write_json(
        mem / "attempts" / "attempt-812-1" / "20260910T1422.json",
        attempt_record("812", "attempt-812-1", "SUBMIT_OK"),
    )
    (mem / "screenshots").mkdir(parents=True, exist_ok=True)
    (mem / "screenshots" / "812.png").write_bytes(b"png-812")
    backend = [backend_job(812, CONSISTENT_URL_B, lifecycle="SUBMITTED")]
    return mem, backend


def build_scenario_orphan_screenshot(tmp: Path):
    """Incident (c): screenshot with no records.

        screenshots/555.png          → present
        applications/ and attempts/  → EMPTY
        backend                      → job 555 exists (lifecycle null)
    """
    mem = tmp / "mem"
    (mem / "screenshots").mkdir(parents=True, exist_ok=True)
    (mem / "screenshots" / "555.png").write_bytes(b"png-555")
    backend = [backend_job(555, "https://jobs.gupy.io/jobs/555", lifecycle=None)]
    return mem, backend


def build_scenario_unknown_attempt_job(tmp: Path):
    """Incident (d): attempt referencing a job id unknown to the backend.

        attempts/<aid>/<ts>.json  → job_id=999
        backend                   → knows 609 only (lifecycle null, so no
                                    secondary divergence pollutes the check)
    """
    mem = tmp / "mem"
    write_json(
        mem / "attempts" / "attempt-999-1" / "20260910T1422.json",
        attempt_record("999", "attempt-999-1", "INCOMPLETE"),
    )
    backend = [backend_job(609, CONSISTENT_URL_A, lifecycle=None)]
    return mem, backend


def build_scenario_consistent(tmp: Path):
    """Incident (e): a fully consistent set → clean.

        609: applied + SUBMIT_OK attempt + screenshot + backend SUBMITTED
        812: backend SUBMITTED + application + SUBMIT_OK attempt + screenshot
    """
    mem = tmp / "mem"
    write_json(mem / "applications" / "609.json", applied_record("609"))
    write_json(mem / "applications" / "812.json", applied_record("812"))
    write_json(
        mem / "attempts" / "attempt-609-1" / "20260910T1422.json",
        attempt_record("609", "attempt-609-1", "SUBMIT_OK"),
    )
    write_json(
        mem / "attempts" / "attempt-812-1" / "20260910T1422.json",
        attempt_record("812", "attempt-812-1", "SUBMIT_OK"),
    )
    (mem / "screenshots").mkdir(parents=True, exist_ok=True)
    (mem / "screenshots" / "609.png").write_bytes(b"png-609")
    (mem / "screenshots" / "812.png").write_bytes(b"png-812")
    backend = [
        backend_job(609, CONSISTENT_URL_A, lifecycle="SUBMITTED"),
        backend_job(812, CONSISTENT_URL_B, lifecycle="SUBMITTED"),
    ]
    return mem, backend


# ---------------------------------------------------------------------------
# Check-level tests (pure, no HTTP, no stdout)
# ---------------------------------------------------------------------------

class BackendKeyMappingTests(unittest.TestCase):
    """backend_job_keys: the portal slug ↔ backend id identity mapping."""

    def test_numeric_slug_from_url_and_id(self):
        keys = audit.backend_job_keys({"id": 609, "url": CONSISTENT_URL_A})
        self.assertEqual(keys, {"609"})

    def test_slug_from_url_only_when_id_missing(self):
        # The map must never key a job with a bare "None".
        keys = audit.backend_job_keys({"id": None, "url": CONSISTENT_URL_A})
        self.assertEqual(keys, {"609"})

    def test_unresolvable_url_drops_slug(self):
        # A URL with no path segment derives no slug — the job only answers
        # to its numeric id (never to the bare string "None").
        keys = audit.backend_job_keys({"id": 42, "url": "https://gupy.io"})
        self.assertEqual(keys, {"42"})


class LoaderTests(unittest.TestCase):
    """Source loaders: read-only, corrupt/missing files degrade to missing."""

    def test_load_applications_reads_applied_records(self):
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            write_json(mem / "applications" / "609.json", applied_record("609"))
            result = audit.load_applications(mem)
        self.assertEqual(set(result), {"609"})
        self.assertEqual(result["609"]["status"], "applied")

    def test_load_applications_ignores_corrupt_json(self):
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            good = write_json(mem / "applications" / "609.json",
                              applied_record("609"))
            (mem / "applications" / "broken.json").write_text(
                "{ not json", encoding="utf-8")
            result = audit.load_applications(mem)
        self.assertEqual(set(result), {"609"})

    def test_load_attempts_finds_flat_and_nested_layouts(self):
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            write_json(mem / "attempts" / "attempt-a" / "20260910T1422.json",
                       attempt_record("609", "attempt-a", "SUBMIT_OK"))
            write_json(mem / "attempts" / "legacy.json",
                       attempt_record("812", "legacy", "INCOMPLETE"))
            result = audit.load_attempts(mem)
        self.assertEqual(set(result), {"609", "812"})
        self.assertEqual([a["outcome"] for a in result["609"]], ["SUBMIT_OK"])

    def test_load_attempts_ignores_corrupt_json(self):
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            write_json(mem / "attempts" / "attempt-a" / "x.json",
                       attempt_record("609", "attempt-a", "SUBMIT_OK"))
            (mem / "attempts" / "broken.json").write_text(
                "{ nope", encoding="utf-8")
            result = audit.load_attempts(mem)
        self.assertEqual(set(result), {"609"})

    def test_load_screenshots_returns_sorted_pngs(self):
        with tempfile.TemporaryDirectory() as td:
            shots = Path(td) / "shots"
            shots.mkdir()
            (shots / "b.png").write_bytes(b"b")
            (shots / "a.png").write_bytes(b"a")
            (shots / "readme.txt").write_text("not a screenshot")
            result = audit.load_screenshots(shots)
        self.assertEqual([p.name for p in result], ["a.png", "b.png"])

    def test_load_screenshots_empty_dir_returns_empty(self):
        with tempfile.TemporaryDirectory() as td:
            result = audit.load_screenshots(Path(td) / "no-shots")
        self.assertEqual(result, [])


# ---------------------------------------------------------------------------
# Divergence-class tests — each real incident, end-to-end via run()
# ---------------------------------------------------------------------------

class DivergenceDetectionTests(unittest.TestCase):
    """Each divergence class detected through the CLI with exit 1 and a
    readable line."""

    def test_609_orphan_all_three_divergences_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_609_orphan(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 1)
        self.assertIn("DIVERGENCE applied_without_backend", out)
        self.assertIn("job=609", out)
        self.assertIn("backend_lifecycle=None", out)
        self.assertIn("DIVERGENCE applied_without_successful_attempt", out)
        self.assertIn("job=609", out)
        self.assertIn("[quarantine]", out)
        self.assertIn("DIVERGENCE applied_without_screenshot", out)
        self.assertIn("SUMMARY: 3 divergence(s)", out)

    def test_609_orphan_hits_list_endpoint_with_bot_token(self):
        # The audit must GET the funnel source (GET /api/jobs) carrying the
        # static service token in the X-Bot-Token header (issue #47).
        calls = []

        def recording_fetch(req, timeout=10):
            calls.append((req.full_url, dict(req.headers)))
            return FakeHttpResponse(200, json.dumps([]).encode("utf-8"))

        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_consistent(Path(td))

            def recording_fetch(req, timeout=10):
                calls.append((req.full_url, dict(req.headers)))
                return FakeHttpResponse(
                    200, json.dumps(backend).encode("utf-8"))

            code, out = run_cli(
                ["--api-base-url", "http://10.0.0.1:8080",
                 "--api-token", "sekret", "--memory-dir", str(mem)],
                fetch=recording_fetch,
            )
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 1)
        self.assertTrue(calls[0][0].startswith("http://10.0.0.1:8080/api/jobs"))
        # urllib normalizes header casing (X-Bot-Token -> X-bot-token): lookup
        # case-insensitively for the service token value (issue #47).
        headers = calls[0][1]
        token = next((v for k, v in headers.items()
                      if k.lower() == "x-bot-token"), None)
        self.assertEqual(token, "sekret")

    def test_backend_submitted_without_application_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_backend_submitted_no_app(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 1)
        self.assertIn("DIVERGENCE backend_submitted_without_application", out)
        self.assertIn("job=812", out)
        self.assertIn("applications=missing", out)
        self.assertIn("SUMMARY: 1 divergence(s)", out)

    def test_orphan_screenshot_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_orphan_screenshot(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 1)
        self.assertIn("DIVERGENCE orphan_screenshot", out)
        self.assertIn("555.png", out)
        self.assertIn("applications=none attempts=none", out)

    def test_attempt_unknown_job_one_line_per_key_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_unknown_attempt_job(Path(td))
            # Two attempt files for the same unknown key: still ONE line.
            write_json(
                mem / "attempts" / "attempt-999-2" / "20260911T0800.json",
                attempt_record("999", "attempt-999-2", "error"),
            )
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 1)
        self.assertIn("DIVERGENCE attempt_unknown_job", out)
        self.assertIn("job=999", out)
        self.assertIn("backend=unknown", out)
        # ONE divergence line for the shared unknown key, grouping BOTH files.
        self.assertEqual(out.count("DIVERGENCE attempt_unknown_job"), 1)
        self.assertIn("attempt-999-1", out)
        self.assertIn("attempt-999-2", out)
        self.assertEqual(out.count("DIVERGENCE"), 1)

    def test_consistent_set_exit_zero(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_consistent(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 0)
        self.assertNotIn("DIVERGENCE", out)
        self.assertIn("SUMMARY: 0 divergences", out)


# ---------------------------------------------------------------------------
# Quarantine semantics + read-only guarantee
# ---------------------------------------------------------------------------

class QuarantineAnnotationTests(unittest.TestCase):
    """A non-verified attempt record is expected state: it is annotated on the
    divergence lines it contributes to, NEVER flagged alone (no double-flag)."""

    def test_quarantine_annotated_not_double_flagged(self):
        # Applied record + SUBMITTED backend + screenshot + ONE failed attempt.
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            write_json(mem / "applications" / "609.json", applied_record("609"))
            write_json(
                mem / "attempts" / "attempt-609-1" / "20260910T1422.json",
                attempt_record("609", "attempt-609-1", "INCOMPLETE",
                               reason="MANUAL"),
            )
            (mem / "screenshots").mkdir(parents=True, exist_ok=True)
            (mem / "screenshots" / "609.png").write_bytes(b"png-609")
            backend = [backend_job(609, CONSISTENT_URL_A,
                                   lifecycle="SUBMITTED")]
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 1)
        # EXACTLY ONE divergence: the applied-without-success line; the
        # quarantine attempt is annotated inside it, not flagged separately.
        self.assertEqual(out.count("DIVERGENCE"), 1)
        self.assertIn("applied_without_successful_attempt", out)
        self.assertIn("attempt-609-1", out)
        self.assertIn("[quarantine]", out)
        self.assertNotIn("DIVERGENCE attempt_unknown_job", out)
        self.assertNotIn("DIVERGENCE applied_without_screenshot", out)

    def test_screenshot_path_field_satisfies_evidence_check(self):
        # The timestamped convention (mcp-apply-loop.md) records an absolute
        # screenshot_path that survives outside the conventional name — the
        # audit must honor the record's own evidence before flagging.
        with tempfile.TemporaryDirectory() as td:
            mem = Path(td) / "mem"
            shot = mem / "screenshots" / "609-20260910T1422.png"
            write_json(mem / "applications" / "609.json",
                       applied_record("609", screenshot_path=str(shot)))
            write_json(
                mem / "attempts" / "attempt-609-1" / "20260910T1422.json",
                attempt_record("609", "attempt-609-1", "SUBMIT_OK"),
            )
            shot.parent.mkdir(parents=True, exist_ok=True)
            shot.write_bytes(b"png-609")
            backend = [backend_job(609, CONSISTENT_URL_A,
                                   lifecycle="SUBMITTED")]
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
        self.assertEqual(code, 0)
        self.assertNotIn("applied_without_screenshot", out)


class ReadOnlyGuaranteeTests(unittest.TestCase):
    """The audit never writes, moves or deletes anything: the full fixture
    tree (paths + bytes) is identical after a divergence-laden run."""

    def test_source_dirs_untouched_by_divergence_run(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_609_orphan(Path(td))
            before = snapshot_tree(mem)
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
            after = snapshot_tree(mem)
        self.assertEqual(code, 1)
        self.assertEqual(before, after)

    def test_source_dirs_untouched_by_clean_run(self):
        with tempfile.TemporaryDirectory() as td:
            mem, backend = build_scenario_consistent(Path(td))
            before = snapshot_tree(mem)
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch(backend),
            )
            after = snapshot_tree(mem)
        self.assertEqual(code, 0)
        self.assertEqual(before, after)


# ---------------------------------------------------------------------------
# Error / usage paths
# ---------------------------------------------------------------------------

class ErrorPathTests(unittest.TestCase):
    """Clean JSON errors, never tracebacks; exit 2 usage / exit 1 api & token."""

    def test_missing_token_clean_error_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, _ = build_scenario_consistent(Path(td))
            buf = io.StringIO()
            with redirect_stdout(buf):
                with unittest.mock.patch.dict(os.environ, {}, clear=True):
                    code = audit.run(["--api-token", "",
                                      "--profile-dir", str(Path(td) / "nope"),
                                      "--memory-dir", str(mem)],
                                     fetch=fake_fetch([]))
        self.assertEqual(code, 1)
        payload = json.loads(buf.getvalue())
        self.assertEqual(payload["error"], "missing_api_token")

    def test_backend_401_maps_to_unauthorized_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, _ = build_scenario_consistent(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch({"detail": "denied"}, status=401),
            )
        self.assertEqual(code, 1)
        payload = json.loads(out)
        self.assertEqual(payload["error"], "unauthorized")

    def test_backend_transport_error_exit_one(self):
        def boom(req, timeout=10):
            raise ConnectionRefusedError("refused")

        with tempfile.TemporaryDirectory() as td:
            mem, _ = build_scenario_consistent(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=boom,
            )
        self.assertEqual(code, 1)
        payload = json.loads(out)
        self.assertEqual(payload["error"], "api_error")

    def test_backend_non_list_payload_exit_one(self):
        with tempfile.TemporaryDirectory() as td:
            mem, _ = build_scenario_consistent(Path(td))
            code, out = run_cli(
                ["--api-base-url", "http://localhost:8080",
                 "--api-token", "tok", "--memory-dir", str(mem)],
                fetch=fake_fetch({"not": "a list"}),
            )
        self.assertEqual(code, 1)
        payload = json.loads(out)
        self.assertEqual(payload["error"], "api_error")


if __name__ == "__main__":
    unittest.main()