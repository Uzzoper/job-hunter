#!/usr/bin/env python3
"""
apply_test.py — issue #37 tests for apply.py (structured Gupy application planning).

Plain unittest (pytest-compatible). apply.py is pure orchestration: it never
touches a browser or the network, so CLI-level tests run via subprocess.

Run:
    python3 apply_test.py
    python3 -m pytest apply_test.py
"""

import http.server
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import unittest.mock
from contextlib import redirect_stdout
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apply  # noqa: E402  (RED phase: module does not exist yet)
import intent  # noqa: E402  (RED phase: module does not exist yet)
import verdict  # noqa: E402  (cutover pkg 2: sole writer of the applied record)

APPLY_PATH = Path(__file__).resolve().parent / "apply.py"
PORTAL = "gupy"
GUPY_URL = "https://jobs.gupy.io/jobs/12345-desenvolvedor-java"
JOB_ID = "12345-desenvolvedor-java"


def _write_applied_record(apps: Path, key: str, backend_id) -> Path:
    """Test fixture: write an applied verdict record under applications/.

    Mirrors verdict.write_applied_record's record shape (status applied +
    backend_job_id). backend_id is written as-is, so the type-safe reader
    comparison is exercised with either an int or a str.
    """
    apps.mkdir(parents=True, exist_ok=True)
    record = apps / f"{key}.json"
    record.write_text(
        json.dumps({
            "job_id": key,
            "status": "applied",
            "backend_job_id": backend_id,
        }),
        encoding="utf-8",
    )
    return record

VALID_PROFILE = {
    "name": "Juan Antonio Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 42 99999-0000",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga de desenvolvedor.",
}


class _MockCdpHandler(http.server.BaseHTTPRequestHandler):
    """Minimal fake CDP endpoint: responds 200 to /json/version.

    Lets CLI subprocess tests exercise the issue #39 browser-recovery path
    without a real browser: ensure_browser() sees CDP as reachable and reports
    status "ready", so the intent is emitted normally.
    """

    def do_GET(self):
        if self.path == "/json/version":
            body = json.dumps({"Browser": "Chrome/0.0.0.0 (mock CDP)"}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args):  # keep test output clean
        pass


def _start_mock_cdp():
    """Start a mock CDP HTTP server on an ephemeral port; return (server, port)."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _MockCdpHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, port


# Header/driver helper: the browser-recovery check in CLI subprocesses targets
# this mock CDP endpoint so no real browser is ever launched during tests.
MOCK_CDP_SERVER, MOCK_CDP_PORT = _start_mock_cdp()
MOCK_CDP_URL = f"http://127.0.0.1:{MOCK_CDP_PORT}"
# A port we can rely on being closed (reserved range; never bound by the server).
CLOSED_CDP_URL = "http://localhost:19222"


class RunResult:
    """Thin wrapper around a subprocess run (exit code + parsed JSON stdout)."""

    def __init__(self, code: int, data):
        self.code = code
        self.data = data


def write_profile(memory_dir, profile=None):
    """Write a profile JSON into the given dir and return its path."""
    path = Path(memory_dir) / "profile.json"
    data = profile if profile is not None else VALID_PROFILE
    path.write_text(json.dumps(data), encoding="utf-8")
    return str(path)


def run_cli(memory_dir, profile_path, *args, portal=PORTAL, url=GUPY_URL,
            cdp_url=MOCK_CDP_URL):
    """Run apply.py via subprocess with the given port flags and parse stdout.

    ``cdp_url`` defaults to the in-process mock CDP server so the issue #39
    browser check reports "ready" and the intent emission path is reached. Pass
    ``cdp_url=None`` (or an explicit ``--cdp-url`` in *args) to override.
    """
    cmd = [
        sys.executable, str(APPLY_PATH),
        "--job-url", url,
        "--portal", portal,
        "--memory-dir", str(memory_dir),
        "--profile", profile_path,
        # PR #80 review P0-1 — the preflight gate is DEFAULT-ON on real runs;
        # these subprocess tests target OTHER gates, so opt out explicitly.
        # (PreflightGateTests above cover the gate itself in-process, without
        # --skip-preflight-check.)
        "--skip-preflight-check",
    ]
    if cdp_url:
        cmd += ["--cdp-url", cdp_url]
    cmd.extend(args)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        data = {"raw_stdout": proc.stdout}
    return RunResult(proc.returncode, data)


# ---------------------------------------------------------------------------
# Profile validation
# ---------------------------------------------------------------------------

class ProfileValidationTests(unittest.TestCase):
    """Missing/invalid profile inputs produce invalid_profile JSON errors."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)

    def test_missing_phone_field(self):
        profile = {k: v for k, v in VALID_PROFILE.items() if k != "phone"}
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path)
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "invalid_profile")
        self.assertIn("phone", result.data["detail"])

    def test_empty_cover_text_rejected(self):
        profile = dict(VALID_PROFILE, cover_text="")
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path)
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "invalid_profile")
        self.assertIn("cover_text", result.data["detail"])

    def test_profile_file_not_found(self):
        missing = Path(self.mem) / "does-not-exist.json"
        result = run_cli(self.mem, str(missing))
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "invalid_profile")
        self.assertIn("not found", result.data["detail"])

    def test_profile_invalid_json(self):
        path = Path(self.mem) / "bad.json"
        path.write_text("not json at all {", encoding="utf-8")
        result = run_cli(self.mem, str(path))
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "invalid_profile")

    def test_invalid_profile_error_has_screenshot_hint(self):
        profile = {k: v for k, v in VALID_PROFILE.items() if k != "phone"}
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path)
        self.assertIn("screenshot_path", result.data)


# ---------------------------------------------------------------------------
# Refusal guardrail (#28)
# ---------------------------------------------------------------------------

class RefusalTests(unittest.TestCase):
    """NO_APPLY / refusal markers must block planning entirely."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)

    def test_no_apply_flag_blocks(self):
        profile = dict(VALID_PROFILE, no_apply=True)
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path)
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "refusal_draft_blocked")

    def test_cover_text_refusal_marker_blocks(self):
        profile = dict(VALID_PROFILE, cover_text="NO_APPLY: stack mismatch")
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path)
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "refusal_draft_blocked")

    def test_plain_cover_not_blocked(self):
        path = write_profile(self.mem)
        result = run_cli(self.mem, path, "--dry-run")
        self.assertEqual(result.code, 0)
        self.assertIn("intent", result.data)
        self.assertNotIn("error", result.data)


# ---------------------------------------------------------------------------
# Confirm gate — intent policy (cutover pkg 3: the selector plan with
# submit/confirm_checkpoint steps is gone; the intent carries the policy).
# ---------------------------------------------------------------------------

class ConfirmGateTests(unittest.TestCase):
    """Confirmation policy flips via --confirmed/--auto-apply; hard gates stay."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def policy(self, result):
        return result.data["intent"]["policy"]

    def test_without_confirmed_requires_confirmation(self):
        result = run_cli(self.mem, self.profile_path, "--dry-run")
        self.assertEqual(result.code, 0)
        self.assertNotIn("steps", result.data)
        self.assertTrue(self.policy(result)["require_confirmation_before_final_submit"])

    def test_with_confirmed_flips_policy(self):
        result = run_cli(self.mem, self.profile_path, "--dry-run", "--confirmed")
        self.assertEqual(result.code, 0)
        self.assertFalse(self.policy(result)["require_confirmation_before_final_submit"])

    def test_auto_apply_flips_policy_and_hard_gates_stay(self):
        auto = run_cli(self.mem, self.profile_path, "--dry-run", "--auto-apply")
        self.assertEqual(auto.code, 0)
        self.assertFalse(self.policy(auto)["require_confirmation_before_final_submit"])
        # never_fill_credentials / stop_on_auth_url are not CLI-disablable.
        plain = run_cli(self.mem, self.profile_path, "--dry-run")
        self.assertTrue(plain.data["intent"]["policy"]["never_fill_credentials"])
        self.assertTrue(plain.data["intent"]["policy"]["stop_on_auth_url"])
        self.assertTrue(auto.data["intent"]["policy"]["never_fill_credentials"])
        self.assertTrue(auto.data["intent"]["policy"]["stop_on_auth_url"])


# ---------------------------------------------------------------------------
# Issue #46 — API-first flow: Job Hunter API as the primary job source
# (--job-id detail prefill / --from-api top-scored job / 401 clean JSON).
# ---------------------------------------------------------------------------

class ApiFirstTests(unittest.TestCase):
    """apply.py API-first wiring (issue #46). The network boundary (job_api
    functions) is mocked — never a real HTTP call. API-first metadata lands in
    the intent envelope; the classic --job-url flow emits an intent without it."""

    API_JOB = {
        "id": 7,
        "title": "Desenvolvedor Java Pleno",
        "company": "Acme Corp",
        "url": "https://jobs.gupy.io/jobs/777-java-pleno",
        "description": "Backend Java 21, Spring Boot",
        "postedAt": "2026-09-01",
        "source": "gupy",
        "contactEmail": "rh@acme.example",
    }

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def _run_api(self, *args):
        """Run apply.run() in-process with the standard API-first flags and
        capture (exit_code, stdout). --dry-run keeps the run off the browser."""
        cmd = list(args) + [
            "--profile", self.profile_path,
            "--memory-dir", str(self.mem),
            "--portal", "gupy",
            "--dry-run",
        ]
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = apply.run(cmd)
        return code, buf.getvalue()

    @unittest.mock.patch("apply.job_api.api_get_job")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_job_id_prefills_url_title_company(self, mock_token, mock_get):
        """--job-id fetches the detail and pre-fills jobUrl/title/company."""
        mock_token.return_value = "tok-1"
        mock_get.return_value = self.API_JOB
        code, out = self._run_api("--job-id", "7")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["intent"]["job_url"], self.API_JOB["url"])
        self.assertEqual(data["intent"]["metadata"]["job_title"], self.API_JOB["title"])
        self.assertEqual(data["intent"]["metadata"]["job_company"], self.API_JOB["company"])
        self.assertEqual(data["intent"]["job_id"], "777-java-pleno")
        self.assertNotIn("steps", data)
        mock_get.assert_called_once()
        base, token, jid = mock_get.call_args[0]
        self.assertEqual(base, "http://localhost:8080")
        self.assertEqual(token, "tok-1")
        self.assertEqual(jid, 7)

    @unittest.mock.patch("apply.job_api.pick_jobs_for_apply")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_from_api_selects_top_job(self, mock_token, mock_pick):
        """--from-api plans from the top-scored job returned by the API."""
        mock_token.return_value = "tok-1"
        jobs = [
            {**self.API_JOB, "id": 8, "url": "https://jobs.gupy.io/jobs/888-a",
             "matchScore": 90},
            {**self.API_JOB, "id": 7, "url": "https://jobs.gupy.io/jobs/777-b",
             "matchScore": 70},
        ]
        mock_pick.return_value = jobs
        code, out = self._run_api("--from-api")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["intent"]["job_url"], jobs[0]["url"])
        self.assertEqual(data["intent"]["metadata"]["job_title"], jobs[0]["title"])
        self.assertEqual(data["intent"]["metadata"]["job_company"], jobs[0]["company"])
        self.assertEqual(data["intent"]["job_id"], "888-a")
        mock_pick.assert_called_once()
        kwargs = mock_pick.call_args.kwargs
        self.assertEqual(kwargs.get("fetch_if_empty"), True)
        self.assertEqual(kwargs.get("portal"), "gupy")
        self.assertIsNone(kwargs.get("min_score"))

    @unittest.mock.patch("apply.job_api.pick_jobs_for_apply")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_from_api_min_score_and_no_fetch_flags_forwarded(self, mock_token, mock_pick):
        """--min-score and --no-fetch-if-empty are passed through to the picker."""
        mock_token.return_value = "tok-1"
        mock_pick.return_value = [self.API_JOB]
        code, out = self._run_api(
            "--from-api", "--min-score", "60", "--no-fetch-if-empty"
        )
        self.assertEqual(code, 0)
        kwargs = mock_pick.call_args.kwargs
        self.assertEqual(kwargs.get("min_score"), 60)
        self.assertFalse(kwargs.get("fetch_if_empty"))

    @unittest.mock.patch("apply.job_api.pick_jobs_for_apply")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_from_api_custom_base_url_and_token(self, mock_token, mock_pick):
        """--api-base-url and --api-token are forwarded to the picker."""
        mock_token.return_value = "flag-tok"
        mock_pick.return_value = [self.API_JOB]
        code, out = self._run_api(
            "--from-api", "--api-base-url", "http://10.0.0.1:9000",
            "--api-token", "flag-tok",
        )
        self.assertEqual(code, 0)
        kwargs = mock_pick.call_args.kwargs
        self.assertEqual(kwargs.get("base_url"), "http://10.0.0.1:9000")
        self.assertEqual(kwargs.get("token"), "flag-tok")

    @unittest.mock.patch("apply.job_api.pick_jobs_for_apply")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_401_maps_to_clean_json_exit_1(self, mock_token, mock_pick):
        """401 → {"error": "unauthorized"} printed as clean JSON, exit 1."""
        mock_token.return_value = "tok-1"
        mock_pick.return_value = {
            "error": "unauthorized", "detail": "401 Unauthorized",
        }
        code, out = self._run_api("--from-api")
        self.assertEqual(code, 1)
        data = json.loads(out)
        self.assertEqual(data["error"], "unauthorized")

    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_missing_api_token_clean_json_exit_1(self, mock_token):
        """Missing token → {"error": "missing_api_token"} clean JSON, exit 1."""
        mock_token.return_value = {
            "error": "missing_api_token",
            "detail": "Token da API do Job Hunter não encontrado.",
        }
        code, out = self._run_api("--from-api")
        self.assertEqual(code, 1)
        data = json.loads(out)
        self.assertEqual(data["error"], "missing_api_token")

    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_classic_job_url_flow_does_not_call_api(self, mock_token):
        """No API flags → behavior unchanged, job_api is never touched."""
        code, out = self._run_api("--job-url", GUPY_URL)
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["intent"]["job_url"], GUPY_URL)
        self.assertNotIn("metadata", data["intent"])
        mock_token.assert_not_called()

    @unittest.mock.patch("apply.job_api.api_get_job")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_already_applied_via_backend_id_fallback(self, mock_token, mock_get):
        """Miss-by-slug + hit-by-backend-id → already_applied, exit 1."""
        mock_token.return_value = "tok-1"
        mock_get.return_value = {
            "id": 42,
            "url": "https://jobs.gupy.io/jobs/numeric-key-42",
            "title": "Dev",
            "company": "Acme",
        }
        # The record lives under a DIFFERENT slug (base64 key) but carries the
        # same backend id — the reader's backend_job_id fallback must find it.
        _write_applied_record(self.mem / "applications", "base64-other-key", 42)
        code, out = self._run_api("--job-id", "42")
        self.assertEqual(code, 1)
        data = json.loads(out)
        self.assertEqual(data["error"], "already_applied")

    @unittest.mock.patch("apply.job_api.api_get_job")
    @unittest.mock.patch("apply.job_api.resolve_token")
    def test_miss_both_ways_still_plans(self, mock_token, mock_get):
        """Slug file absent AND no matching backend id → normal planning."""
        mock_token.return_value = "tok-1"
        mock_get.return_value = self.API_JOB  # id 7, url .../jobs/777-java-pleno
        # A record for a DIFFERENT backend id must not false-positive.
        _write_applied_record(self.mem / "applications", "some-other", 99)
        code, out = self._run_api("--job-id", "7")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertIn("intent", data)
        self.assertEqual(data["intent"]["job_id"], "777-java-pleno")

    def test_from_api_requires_profile(self):
        """API-first still requires --profile (the apply data)."""
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = apply.run(["--from-api"])
        self.assertEqual(code, 2)
        data = json.loads(buf.getvalue())
        self.assertEqual(data["error"], "usage")

    def test_parse_args_api_defaults(self):
        args = apply.parse_args([])
        self.assertIsNone(args.job_id)
        self.assertIsNone(args.api_token)
        self.assertIsNone(args.profile_dir)
        self.assertIsNone(args.min_score)
        self.assertEqual(args.api_base_url, "http://localhost:8080")
        self.assertTrue(args.fetch_if_empty)
        self.assertFalse(args.from_api)

    def test_parse_args_api_flags_parsed(self):
        args = apply.parse_args([
            "--job-id", "42",
            "--api-base-url", "http://10.0.0.1:9000",
            "--api-token", "abc",
            "--profile-dir", "/tmp/prof",
            "--min-score", "60",
            "--from-api",
        ])
        self.assertEqual(args.job_id, "42")
        self.assertEqual(args.api_base_url, "http://10.0.0.1:9000")
        self.assertEqual(args.api_token, "abc")
        self.assertEqual(args.profile_dir, "/tmp/prof")
        self.assertEqual(args.min_score, 60)
        self.assertTrue(args.from_api)

    def test_parse_args_no_fetch_if_empty(self):
        args = apply.parse_args(["--no-fetch-if-empty"])
        self.assertFalse(args.fetch_if_empty)


# ---------------------------------------------------------------------------
# Job-id derivation
# ---------------------------------------------------------------------------

class JobIdTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_derive_job_id_variants(self):
        self.assertEqual(
            apply.derive_job_id("https://jobs.gupy.io/jobs/123-dev"), "123-dev"
        )
        self.assertEqual(
            apply.derive_job_id("https://acme.gupy.io/jobs/456-front/"),
            "456-front",
        )
        self.assertEqual(
            apply.derive_job_id("https://jobs.gupy.io/jobs/789-x?ref=1"),
            "789-x",
        )
        self.assertIsNone(apply.derive_job_id("https://example.com"))
        self.assertIsNone(apply.derive_job_id(""))

    def test_invalid_job_url_errors(self):
        path = write_profile(Path(self.tmp.name))
        result = run_cli(Path(self.tmp.name), path, url="https://example.com")
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "invalid_job_url")


# ---------------------------------------------------------------------------
# Job-id derivation hardening (idempotency-key chain)
# ---------------------------------------------------------------------------

class JobIdHardeningTests(unittest.TestCase):
    """derive_job_id: only recognized job-detail shapes mint a key.

    Old numeric slugs (InfoJobs, e.g. 755694375.json) and the new base64 Gupy
    slugs are both legal, coexisting keys. Application-flow URLs
    (candidates/applications/.../curriculum) and query/fragment-only garbage
    must NEVER become a key: unknown shapes return None so the planner refuses
    to plan instead of aliasing thousands of distinct jobs under one filename.
    """

    def test_numeric_slug_stable(self):
        # Legacy InfoJobs numeric slug format — one trailing .json is stripped
        # (extension hygiene) so the record key is 755694375, never .json.json.
        self.assertEqual(
            apply.derive_job_id("https://www.infojobs.com.br/vaga/755694375.json"),
            "755694375",
        )

    def test_base64_slug_stable(self):
        # New Gupy base64url slug format — unchanged, still a legal key.
        slug = "eyJpZCI6Ijc1NTY5NDM3NSIsInRpdGxlIjoiZGVzZW52b2x2ZWRvci1qYXZhIn0"
        self.assertEqual(
            apply.derive_job_id(f"https://jobs.gupy.io/jobs/{slug}"),
            slug,
        )

    def test_candidates_flow_url_returns_none(self):
        # The apply-in-progress URL must never fall back to a garbage key like
        # "/curriculum" — that would alias every in-progress application.
        self.assertIsNone(apply.derive_job_id(
            "https://mtpbrasil.gupy.io/candidates/applications/xyz/steps/123/curriculum"
        ))

    def test_flow_shapes_return_none(self):
        # Application-flow SHAPES (the applications area, the steps→curriculum
        # chain, and the bare /jobs directory) must yield None — never a wrong
        # key. Lone keyword segments are handled by the legal-detail tests.
        for url in (
            "https://mtpbrasil.gupy.io/candidates/applications",
            "https://mtpbrasil.gupy.io/candidates/applications/xyz/steps",
            "https://mtpbrasil.gupy.io/candidates/applications/xyz/steps/1/curriculum",
            "https://portal.gupy.io/jobs",  # bare /jobs directory, no slug
        ):
            self.assertIsNone(apply.derive_job_id(url), f"expected None for {url}")

    def test_steps_without_curriculum_still_refuses(self):
        # PR #61 finding: a numbered steps/<n> hop is in-flow BY ITSELF — the
        # trailing /curriculum segment is not required. .../steps/1 must never
        # mint the step index "1" as a job slug.
        self.assertIsNone(apply.derive_job_id(
            "https://portal.gupy.io/candidates/steps/1"))
        self.assertIsNone(apply.derive_job_id(
            "https://mtpbrasil.gupy.io/candidates/steps/1/"))
        self.assertIsNone(apply.derive_job_id(
            "https://portal.gupy.io/candidates/steps/1/curriculum"))

    def test_step_singular_numeric_segment_refuses(self):
        # PR #61 finding: the singular "step/<n>" hop is in-flow too — both
        # .../step/1/curriculum and a bare .../step/1 hold no job slug.
        self.assertIsNone(apply.derive_job_id(
            "https://portal.gupy.io/candidates/step/1/curriculum"))
        self.assertIsNone(apply.derive_job_id(
            "https://portal.gupy.io/candidates/step/1"))

    def test_legal_non_gupy_detail_with_candidates_segment_derives(self):
        # PR #60 fallout: a legal detail URL whose path contains a lone
        # /candidates/<id> segment is NOT an application-flow page — it must
        # still derive its slug. Only in-flow segment SEQUENCES refuse.
        self.assertEqual(
            apply.derive_job_id("https://company.example/candidates/12345"),
            "12345",
        )
        self.assertEqual(
            apply.derive_job_id("https://www.infojobs.com.br/candidates/888-front/"),
            "888-front",
        )

    def test_slug_literally_named_steps_derives(self):
        # PR #60 fallout: a slug literally named "steps" is legal — only the
        # in-flow steps→curriculum SHAPE refuses, never the lone keyword.
        self.assertEqual(
            apply.derive_job_id("https://www.infojobs.com.br/vaga/steps"),
            "steps",
        )
        self.assertEqual(apply.derive_job_id("https://jobs.gupy.io/jobs/steps"), "steps")

    def test_json_suffix_stripped_once_case_insensitive(self):
        # Extension hygiene (PR #60 fallout): strip ONE trailing .json from the
        # derived id regardless of case before the reader appends .json; any
        # non-.json slug is untouched.
        for ext in (".json", ".JSON", ".Json"):
            self.assertEqual(
                apply.derive_job_id(
                    f"https://www.infojobs.com.br/vaga/755694375{ext}"),
                "755694375",
            )
        self.assertEqual(
            apply.derive_job_id("https://jobs.gupy.io/jobs/123-dev"), "123-dev")
        self.assertEqual(
            apply.derive_job_id("https://jobs.gupy.io/jobs/planilha-2026.xls"),
            "planilha-2026.xls")

    def test_query_and_fragment_strip_to_same_slug(self):
        bare = "https://jobs.gupy.io/jobs/789-x"
        self.assertEqual(apply.derive_job_id(bare + "?ref=share"), "789-x")
        self.assertEqual(apply.derive_job_id(bare + "#secao-candidatura"), "789-x")
        self.assertEqual(
            apply.derive_job_id(bare + "?utm_source=linkedin&utm_campaign=bot#top"),
            "789-x",
        )


class JobIdIdempotencyKeyTests(unittest.TestCase):
    """Cross-format key resolution: deterministic per format, coexisting across."""

    def test_same_job_resolves_consistently(self):
        # The same detail URL always yields the same idempotency key, with or
        # without tracking query/fragment parts.
        url = "https://jobs.gupy.io/jobs/abc-xyz"
        self.assertEqual(apply.derive_job_id(url), "abc-xyz")
        self.assertEqual(apply.derive_job_id(url + "?ref=1#top"), "abc-xyz")
        self.assertEqual(
            apply.derive_job_id(url),
            apply.derive_job_id(url + "?ref=1#top"),
        )

    def test_numeric_and_base64_slugs_are_distinct_legal_keys(self):
        # Both formats exist in production and both resolve — they are
        # intentionally DIFFERENT memory keys (different filenames); that is
        # documented and legal. The verdict record body carries backend_job_id
        # so a slug-miss can still reconcile against the backend id.
        numeric = apply.derive_job_id(
            "https://www.infojobs.com.br/vaga/755694375.json"
        )
        base64 = apply.derive_job_id(
            "https://jobs.gupy.io/jobs/"
            "eyJpZCI6Ijc1NTY5NDM3NSIsInRpdGxlIjoiZGVzZW52b2x2ZWRvci1qYXZhIn0"
        )
        self.assertIsNotNone(numeric)
        self.assertIsNotNone(base64)
        self.assertEqual(numeric, "755694375")  # extension hygiene: no .json in the id
        self.assertNotEqual(numeric, base64)
        self.assertNotEqual(f"{numeric}.json", f"{base64}.json")


# ---------------------------------------------------------------------------
# Issue #82 — URL and job_id integrity validation: a '.' (hence '...') is
# impossible in a genuine Gupy slug (urlsafe base64), so a truncated /
# hand-typed URL must hard-fail BEFORE an intent is emitted or anything is
# written (the LLM elides the long publicId as '...' and apply.py accepted it
# verbatim — the #82 root cause). Non-Gupy slugs are unaffected.
# ---------------------------------------------------------------------------

class CorruptGupySlugTests(unittest.TestCase):
    """The entry path rejects Gupy URLs whose slug carries a '.' / '...'
    marker; genuine base64 and non-Gupy slugs keep working."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def test_derive_job_id_keeps_verbatim_slug_but_validation_rejects(self):
        # derive_job_id remains the verbatim identity derivation; the integrity
        # gate is is_corrupt_gupy_slug — a dotted slug is corrupt no matter how
        # it was derived.
        url = "https://mendelics.gupy.io/job/eyJqb2...sIn0="
        job_id = apply.derive_job_id(url)
        self.assertIsNotNone(job_id)
        self.assertTrue(apply.is_corrupt_gupy_slug(job_id))

    def test_gupy_url_with_ellipsis_in_slug_rejected_before_intent(self):
        result = run_cli(
            self.mem, self.profile_path, "--dry-run",
            url="https://mendelics.gupy.io/job/eyJqb2...sIn0=",
        )
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "corrupt_gupy_slug")
        self.assertNotIn("intent", result.data)

    def test_gupy_url_with_dot_in_slug_rejected(self):
        # Item A: any '.' — not only '...' — is impossible in a genuine slug.
        result = run_cli(
            self.mem, self.profile_path, "--dry-run",
            url="https://jobs.gupy.io/jobs/eyJqb2.x",
        )
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "corrupt_gupy_slug")
        self.assertNotIn("intent", result.data)

    def test_truncated_slug_writes_no_record_files(self):
        # Item B: hard fail BEFORE any intent file is written — nothing on
        # disk in applications/ attempts/ answers/ screenshots/ (the fixture's
        # profile.json is not a record and is out of scope).
        run_cli(
            self.mem, self.profile_path, "--dry-run",
            url="https://mendelics.gupy.io/job/eyJqb2...sIn0=",
        )
        record_dirs = self.mem / "applications", self.mem / "attempts", \
            self.mem / "answers", self.mem / "screenshots"
        self.assertEqual(
            [p for d in record_dirs if d.exists() for p in d.rglob("*")], [])

    def test_genuine_base64_slug_with_padding_still_passes(self):
        # Item D: urlsafe base64 + trailing '=' is legal — keep planning.
        slug = "eyJpZCI6Ijc1NTY5NDM3NSIsInRpdGxlIjoiZGVzZW52b2x2ZWRvci1qYXZhIn0="
        result = run_cli(
            self.mem, self.profile_path, "--dry-run",
            url=f"https://jobs.gupy.io/jobs/{slug}",
        )
        self.assertEqual(result.code, 0)
        self.assertEqual(result.data["intent"]["job_id"], slug)

    def test_infojobs_numeric_slug_unaffected(self):
        # Item E: InfoJobs numeric slug (with the .json extension, which the
        # derivation strips) keeps planning; no dot survives into the id.
        result = run_cli(
            self.mem, self.profile_path, "--dry-run", portal="infojobs",
            url="https://www.infojobs.com.br/vaga/755694375.json",
        )
        self.assertEqual(result.code, 0)
        self.assertEqual(result.data["intent"]["job_id"], "755694375")

    def test_plain_kebab_slug_unaffected(self):
        # Item E: normal human-readable slugs keep working.
        result = run_cli(
            self.mem, self.profile_path, "--dry-run",
            url="https://jobs.gupy.io/jobs/12345-desenvolvedor-java",
        )
        self.assertEqual(result.code, 0)
        self.assertEqual(result.data["intent"]["job_id"], "12345-desenvolvedor-java")

    def test_linkedin_jobs_view_slug_unaffected_by_guard(self):
        # Item E (e): a LinkedIn /jobs/view/<id>/ URL derives the literal
        # "view" segment (JOB_SLUG_RE), never a dotted id — neither the
        # derived slug nor the numeric segment trips the corruption guard.
        self.assertEqual(
            apply.derive_job_id(
                "https://www.linkedin.com/jobs/view/4123456789/"), "view")
        self.assertFalse(apply.is_corrupt_gupy_slug("view"))
        self.assertFalse(apply.is_corrupt_gupy_slug("4123456789"))

    def test_api_mode_with_truncated_url_also_rejected(self):
        # Item A: the gate keys on the DERIVED id, so an API-provided URL is
        # validated too — a corrupt backend row must never emit an intent.
        with unittest.mock.patch("job_api.resolve_token", return_value="tok"), \
                unittest.mock.patch("job_api.api_get_job", return_value={
                    "id": 421,
                    "url": "https://mendelics.gupy.io/job/eyJqb2...sIn0=",
                    "title": "Dev",
                    "company": "Acme",
                }):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--job-id", "421",
                    "--dry-run",
                ])
        self.assertEqual(code, 1)
        data = json.loads(buf.getvalue())
        self.assertEqual(data["error"], "corrupt_gupy_slug")
        self.assertNotIn("intent", buf.getvalue())


# ---------------------------------------------------------------------------
# Idempotency (#27) — memory record round-trip
# ---------------------------------------------------------------------------

class IdempotencyTests(unittest.TestCase):
    """already_applied short-circuit and record round-trip in a tmp dir."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)
        self.record_path = self.mem / "applications" / f"{JOB_ID}.json"

    def test_record_applied_flag_rejected(self):
        # PR #58 review finding: --record-applied is dead — verdict.py is the
        # sole recorder, so the flag was removed and must fail loudly
        # (unrecognized argument, exit 2) instead of being accepted as a no-op.
        result = run_cli(self.mem, self.profile_path, "--record-applied")
        self.assertEqual(result.code, 2)
        self.assertEqual(result.data["error"], "usage")
        # Nothing was recorded on disk either (apply.py never writes records).
        self.assertFalse(self.record_path.exists())

    def test_confirmed_run_writes_no_record(self):
        run_cli(self.mem, self.profile_path, "--confirmed")
        self.assertFalse(self.record_path.exists())

    def test_dry_run_writes_nothing(self):
        run_cli(self.mem, self.profile_path, "--dry-run")
        dry = run_cli(self.mem, self.profile_path, "--confirmed", "--dry-run")
        self.assertIn("intent", dry.data)
        self.assertFalse(self.record_path.exists())

    def test_existing_applied_record_short_circuits(self):
        self.record_path.parent.mkdir(parents=True, exist_ok=True)
        self.record_path.write_text(
            json.dumps(
                {
                    "job_id": JOB_ID,
                    "contact_email": VALID_PROFILE["email"],
                    "portal": "gupy",
                    "applied_at": "2026-01-01T00:00:00+00:00",
                    "screenshot_path": None,
                    "status": "applied",
                }
            ),
            encoding="utf-8",
        )
        result = run_cli(self.mem, self.profile_path)
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "already_applied")

    def test_verdict_written_record_round_trips(self):
        # Cutover pkg 2: verdict.write_applied_record is the SOLE writer of the
        # applied record; apply's check_idempotency gate must still read it and
        # block the planner (already_applied).
        rec = verdict.write_applied_record(
            self.mem, "abc-xyz",
            portal="gupy",
            contact_email=VALID_PROFILE["email"],
            applied_at="2026-01-01T00:00:00+00:00",
            screenshot_path=None,
            verdict=verdict.SUBMIT_OK,
            evidence={"method": "success_text", "found": "Inscrição realizada"},
        )
        self.assertEqual(rec["status"], "applied")
        self.assertEqual(rec["contact_email"], VALID_PROFILE["email"])
        loaded = apply.check_idempotency(self.mem, "abc-xyz")
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["status"], "applied")
        self.assertEqual(loaded["job_id"], "abc-xyz")
        result = run_cli(
            self.mem, self.profile_path,
            url="https://jobs.gupy.io/jobs/abc-xyz",
        )
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "already_applied")

    def test_check_idempotency_backend_id_fallback(self):
        # PR #60 fallout: the verdict record carries backend_job_id (write
        # side); the reader must fall back to it on a slug-file miss, so a
        # numeric/base64 key mismatch for the same real job still kills the
        # plan instead of re-applying.
        apps = self.mem / "applications"
        _write_applied_record(apps, "base64-other-key", 42)
        hit = apply.check_idempotency(self.mem, "wanted-slug", backend_job_id=42)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["job_id"], "base64-other-key")
        # A different backend id (or none) must not false-positive on the scan.
        self.assertIsNone(
            apply.check_idempotency(self.mem, "wanted-slug", backend_job_id=99))
        self.assertIsNone(apply.check_idempotency(self.mem, "wanted-slug"))
        self.assertFalse((apps / "wanted-slug.json").exists())

    def test_backend_id_scan_str_record_matches_int_query(self):
        # PR #61 finding: the record body may hold the id as a str while the
        # caller queries with the api int — both sides must compare as str.
        apps = self.mem / "applications"
        _write_applied_record(apps, "base64-other-key", "42")
        hit = apply.check_idempotency(self.mem, "wanted-slug", backend_job_id=42)
        self.assertIsNotNone(hit)
        self.assertEqual(hit["job_id"], "base64-other-key")

    def test_backend_id_scan_int_record_matches_str_query(self):
        # PR #61 finding: the reverse direction — record int, caller str.
        apps = self.mem / "applications"
        _write_applied_record(apps, "base64-other-key", 42)
        hit = apply.check_idempotency(self.mem, "wanted-slug", backend_job_id="42")
        self.assertIsNotNone(hit)
        self.assertEqual(hit["job_id"], "base64-other-key")

    def test_numeric_slug_round_trips_to_single_json_file(self):
        # Extension hygiene (PR #60 fallout): derive strips one trailing .json,
        # so a legacy InfoJobs URL keys applications/755694375.json — never a
        # doubled .json.json name. Writer and reader both see the same key.
        job_id = apply.derive_job_id(
            "https://www.infojobs.com.br/vaga/755694375.json"
        )
        self.assertEqual(job_id, "755694375")
        verdict.write_applied_record(
            self.mem, job_id,
            portal="infojobs",
            contact_email=VALID_PROFILE["email"],
            applied_at="2026-01-01T00:00:00+00:00",
            screenshot_path=None,
            verdict=verdict.SUBMIT_OK,
            evidence={"method": "success_text", "found": "Inscrição realizada"},
            backend_job_id=42,
        )
        single = self.mem / "applications" / "755694375.json"
        doubled = self.mem / "applications" / "755694375.json.json"
        self.assertTrue(single.is_file())
        self.assertFalse(doubled.exists())
        loaded = apply.check_idempotency(self.mem, job_id)
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["status"], "applied")


# ---------------------------------------------------------------------------
# Usage / CLI
# ---------------------------------------------------------------------------

class UsageTests(unittest.TestCase):
    def test_missing_required_args_emits_usage_json(self):
        proc = subprocess.run(
            [sys.executable, str(APPLY_PATH)], capture_output=True, text=True
        )
        self.assertEqual(proc.returncode, 2)
        data = json.loads(proc.stdout)
        self.assertEqual(data["error"], "usage")

    def test_parse_args_returns_defaults(self):
        args = apply.parse_args([])
        self.assertIsNone(args.job_url)
        self.assertIsNone(args.profile)
        self.assertEqual(args.portal, "gupy")  # issue #45: --portal defaults to gupy
        self.assertIsNone(args.memory_dir)
        self.assertFalse(args.dry_run)
        self.assertFalse(args.confirmed)
        self.assertIsNone(args.cdp_url)
        self.assertIsNone(args.user_data_dir)
        self.assertFalse(args.check_browser)
        self.assertFalse(args.skip_session_check)
        self.assertFalse(args.auto_apply)


# ---------------------------------------------------------------------------
# Browser recovery (issue #39)
# ---------------------------------------------------------------------------

class CheckCdpTests(unittest.TestCase):
    """check_cdp: CDP endpoint reachability."""

    @unittest.mock.patch("urllib.request.urlopen")
    def test_reachable_returns_true(self, mock_urlopen):
        """CDP endpoint responds → check_cdp returns True."""
        mock_urlopen.return_value = unittest.mock.MagicMock(status=200)
        self.assertTrue(apply.check_cdp("http://localhost:9222"))

    @unittest.mock.patch("urllib.request.urlopen")
    def test_connection_refused_returns_false(self, mock_urlopen):
        """CDP endpoint unreachable → check_cdp returns False."""
        mock_urlopen.side_effect = ConnectionRefusedError("connection refused")
        self.assertFalse(apply.check_cdp("http://localhost:9222"))

    @unittest.mock.patch("urllib.request.urlopen")
    def test_timeout_returns_false(self, mock_urlopen):
        """CDP endpoint times out → check_cdp returns False."""
        mock_urlopen.side_effect = TimeoutError("timed out")
        self.assertFalse(apply.check_cdp("http://localhost:9222", timeout=5))

    @unittest.mock.patch("urllib.request.urlopen")
    def test_timeout_kwarg_forwarded(self, mock_urlopen):
        """Timeout parameter is forwarded to urlopen."""
        mock_urlopen.side_effect = OSError("fail")
        apply.check_cdp("http://localhost:9222", timeout=7)
        _, kwargs = mock_urlopen.call_args
        self.assertEqual(kwargs.get("timeout"), 7)


class StartChromiumTests(unittest.TestCase):
    """start_chromium: launch command construction."""

    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    def test_launches_chromium_with_correct_args(self, mock_makedirs, mock_popen):
        """start_chromium calls subprocess.Popen with correct flags."""
        mock_popen.return_value = unittest.mock.MagicMock()
        apply.start_chromium("~/.chromium-profile-cdp")
        cmd = mock_popen.call_args[0][0]
        self.assertEqual(cmd[0], "chromium")
        self.assertIn("--remote-debugging-port=9222", cmd)
        self.assertIn("--no-first-run", cmd)
        expanded = os.path.expanduser("~/.chromium-profile-cdp")
        self.assertTrue(any(expanded in arg for arg in cmd))

    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    def test_expands_user_data_dir(self, mock_makedirs, mock_popen):
        """Tilde in user_data_dir is expanded."""
        mock_popen.return_value = unittest.mock.MagicMock()
        apply.start_chromium("~/my-profile")
        expanded = os.path.expanduser("~/my-profile")
        mock_makedirs.assert_called_once_with(expanded, exist_ok=True)

    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    def test_custom_port(self, mock_makedirs, mock_popen):
        """Custom remote_debugging_port is forwarded."""
        mock_popen.return_value = unittest.mock.MagicMock()
        apply.start_chromium("~/profile", remote_debugging_port=9333)
        cmd = mock_popen.call_args[0][0]
        self.assertIn("--remote-debugging-port=9333", cmd)


class EnsureBrowserTests(unittest.TestCase):
    """ensure_browser: orchestration logic."""

    @unittest.mock.patch("urllib.request.urlopen")
    def test_cdp_already_ready(self, mock_urlopen):
        """CDP reachable on first check → status ready."""
        mock_urlopen.return_value = unittest.mock.MagicMock(status=200)
        result = apply.ensure_browser()
        self.assertEqual(result["status"], "ready")

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_cdp_down_chromium_starts_needs_login(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """CDP down + Chromium starts + re-check ok → needs_login."""
        responses = [OSError("refused"), unittest.mock.MagicMock(status=200)]
        mock_urlopen.side_effect = responses
        mock_popen.return_value = unittest.mock.MagicMock()
        result = apply.ensure_browser()
        self.assertEqual(result["status"], "needs_login")
        mock_popen.assert_called_once()
        mock_sleep.assert_called_once_with(3)

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_chromium_fails_to_start(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """Chromium fails to start → browser_unavailable."""
        mock_urlopen.side_effect = OSError("refused")
        mock_popen.side_effect = FileNotFoundError("chromium not found")
        result = apply.ensure_browser()
        self.assertEqual(result["status"], "browser_unavailable")
        self.assertIn("detail", result)

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_chromium_starts_but_cdp_still_down(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """Chromium started but CDP still unreachable → browser_unavailable."""
        mock_urlopen.side_effect = OSError("refused")
        mock_popen.return_value = unittest.mock.MagicMock()
        result = apply.ensure_browser()
        self.assertEqual(result["status"], "browser_unavailable")

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_needs_login_message_is_ptbr(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """needs_login detail must be in PT-BR."""
        responses = [OSError("refused"), unittest.mock.MagicMock(status=200)]
        mock_urlopen.side_effect = responses
        mock_popen.return_value = unittest.mock.MagicMock()
        result = apply.ensure_browser()
        self.assertIn("Navegador", result["detail"])
        self.assertIn("Chromium", result["detail"])

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_ensure_browser_uses_custom_cdp_url(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """Custom CDP URL is forwarded to check_cdp."""
        mock_urlopen.return_value = unittest.mock.MagicMock(status=200)
        result = apply.ensure_browser(cdp_url="http://10.0.0.1:9333")
        self.assertEqual(result["status"], "ready")
        call_url = mock_urlopen.call_args[0][0]
        self.assertIn("10.0.0.1:9333", call_url)

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_ensure_browser_forwards_custom_port_to_start_chromium(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """Custom remote_debugging_port is forwarded to start_chromium."""
        responses = [OSError("refused"), unittest.mock.MagicMock(status=200)]
        mock_urlopen.side_effect = responses
        mock_popen.return_value = unittest.mock.MagicMock()
        apply.ensure_browser(remote_debugging_port=9333)
        cmd = mock_popen.call_args[0][0]
        self.assertIn("--remote-debugging-port=9333", cmd)


class CheckBrowserCliTests(unittest.TestCase):
    """--check-browser flag: JSON output and exit codes."""

    def _run_check_browser(self):
        """Run apply.run(['--check-browser']) in-process; return (code, parsed JSON)."""
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = apply.run(["--check-browser", "--cdp-url", "http://127.0.0.1:9333"])
        return code, json.loads(buf.getvalue())

    @unittest.mock.patch("urllib.request.urlopen")
    def test_ready_status_json(self, mock_urlopen):
        """CDP reachable → --check-browser prints status ready with exit 0."""
        mock_urlopen.return_value = unittest.mock.MagicMock(status=200)
        code, data = self._run_check_browser()
        self.assertEqual(code, 0)
        self.assertEqual(data["status"], "ready")
        self.assertNotIn("steps", data)

    @unittest.mock.patch("time.sleep")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_needs_login_status_json_exit_zero(self, mock_urlopen, mock_makedirs, mock_popen, mock_sleep):
        """CDP down + Chromium starts → needs_login with exit code 0."""
        mock_urlopen.side_effect = [OSError("refused"), unittest.mock.MagicMock(status=200)]
        mock_popen.return_value = unittest.mock.MagicMock()
        code, data = self._run_check_browser()
        self.assertEqual(code, 0)
        self.assertEqual(data["status"], "needs_login")

    @unittest.mock.patch("os.makedirs")
    @unittest.mock.patch("subprocess.Popen")
    @unittest.mock.patch("urllib.request.urlopen")
    def test_browser_unavailable_status_json_exit_one(self, mock_urlopen, mock_popen, mock_makedirs):
        """Chromium fails to start → browser_unavailable with exit code 1."""
        mock_urlopen.side_effect = OSError("refused")
        mock_popen.side_effect = FileNotFoundError("chromium not found")
        code, data = self._run_check_browser()
        self.assertEqual(code, 1)
        self.assertEqual(data["status"], "browser_unavailable")
        self.assertIn("detail", data)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_check_browser_output_is_json_not_plan(self, mock_urlopen):
        """--check-browser output is a status JSON, never an action plan."""
        mock_urlopen.return_value = unittest.mock.MagicMock(status=200)
        code, data = self._run_check_browser()
        self.assertNotIn("steps", data)
        self.assertNotIn("portal", data)

    @unittest.skipIf(shutil.which("chromium") is not None,
                     "would launch a real Chromium browser")
    def test_check_browser_closed_port_valid_json(self):
        """CLI smoke: --check-browser against closed port returns valid JSON."""
        proc = subprocess.run(
            [sys.executable, str(APPLY_PATH), "--check-browser",
             "--cdp-url", CLOSED_CDP_URL],
            capture_output=True, text=True
        )
        data = json.loads(proc.stdout)
        self.assertIn("status", data)
        self.assertEqual(data["status"], "browser_unavailable")
        self.assertEqual(proc.returncode, 1)


class UserDataDirDefaultTests(unittest.TestCase):
    """user-data-dir default expansion."""

    def test_default_starts_with_tilde(self):
        """DEFAULT_USER_DATA_DIR uses tilde for home expansion."""
        self.assertTrue(apply.DEFAULT_USER_DATA_DIR.startswith("~"))

    def test_default_expands_to_absolute_path(self):
        """Expanding the default user-data-dir yields an absolute path."""
        expanded = os.path.expanduser(apply.DEFAULT_USER_DATA_DIR)
        self.assertTrue(os.path.isabs(expanded))
        self.assertFalse(expanded.startswith("~"))

    def test_default_contains_chromium_profile(self):
        """Default user-data-dir references chromium-profile-cdp."""
        self.assertIn("chromium-profile-cdp", apply.DEFAULT_USER_DATA_DIR)


class DryRunSkipsBrowserCheckTests(unittest.TestCase):
    """--dry-run must skip ensure_browser entirely."""

    def test_dry_run_skips_browser_check(self):
        """With --dry-run, no browser check occurs even on closed port."""
        with tempfile.TemporaryDirectory() as tmpdir:
            mem = Path(tmpdir)
            profile_path = write_profile(mem)
            result = run_cli(mem, profile_path, "--dry-run",
                             "--cdp-url", CLOSED_CDP_URL)
            self.assertEqual(result.code, 0)
            self.assertIn("intent", result.data)
            self.assertTrue(result.data["dry_run"])


class CdpUrlFlagTests(unittest.TestCase):
    """--cdp-url flag is accepted and forwarded."""

    def test_parse_args_has_cdp_url(self):
        """parse_args recognizes --cdp-url."""
        args = apply.parse_args(["--cdp-url", "http://10.0.0.1:9222"])
        self.assertEqual(args.cdp_url, "http://10.0.0.1:9222")

    def test_parse_args_has_user_data_dir(self):
        """parse_args recognizes --user-data-dir."""
        args = apply.parse_args(["--user-data-dir", "/tmp/test-profile"])
        self.assertEqual(args.user_data_dir, "/tmp/test-profile")

    def test_parse_args_has_check_browser(self):
        """parse_args recognizes --check-browser."""
        args = apply.parse_args(["--check-browser"])
        self.assertTrue(args.check_browser)

    def test_parse_args_has_skip_session_check(self):
        """parse_args recognizes --skip-session-check (issue #41)."""
        args = apply.parse_args(["--skip-session-check"])
        self.assertTrue(args.skip_session_check)

    def test_parse_args_has_auto_apply(self):
        """parse_args recognizes --auto-apply (issue #42)."""
        args = apply.parse_args(["--auto-apply"])
        self.assertTrue(args.auto_apply)


# ---------------------------------------------------------------------------
# Planner-intent emission (mcp-apply-loop, phase 3) — --emit-intent
# ---------------------------------------------------------------------------

class IntentEmissionTests(unittest.TestCase):
    """Intent envelope output: shape, policy flips, metadata, no selectors."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)
        self.record_path = self.mem / "applications" / f"{JOB_ID}.json"

    def test_parse_args_has_emit_intent(self):
        args = apply.parse_args(["--emit-intent"])
        self.assertTrue(args.emit_intent)

    def test_parse_args_has_max_steps(self):
        args = apply.parse_args(["--max-steps", "10"])
        self.assertEqual(args.max_steps, 10)

    def test_emit_intent_shape(self):
        result = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run")
        self.assertEqual(result.code, 0)
        data = result.data
        # Envelope: the intent object + dry_run as a sibling execution hint
        # (spec l.111 — dry-run stays OUTSIDE the intent, never in identity/policy).
        self.assertEqual(set(data.keys()), {"intent", "dry_run"})
        intent_obj = data["intent"]
        self.assertEqual(set(intent_obj.keys()),
                         {"intent_id", "job_id", "job_url", "portal",
                          "profile", "policy"})
        self.assertTrue(data["dry_run"])
        self.assertNotIn("dry_run", intent_obj)
        self.assertEqual(intent_obj["job_id"], JOB_ID)
        self.assertEqual(intent_obj["job_url"], GUPY_URL)
        self.assertEqual(intent_obj["portal"], "gupy")
        # Profile carries the applicant data with the intent's resume_path key.
        self.assertEqual(set(intent_obj["profile"].keys()),
                         {"name", "email", "phone", "resume_path", "cover_text"})
        self.assertEqual(intent_obj["profile"]["resume_path"], VALID_PROFILE["cv_path"])
        # Policy block, never individual intent->selector fields.
        self.assertEqual(intent_obj["policy"],
                         {"require_confirmation_before_final_submit": True,
                          "never_fill_credentials": True,
                          "stop_on_auth_url": True,
                          "max_steps": 25})
        # Legacy --job-url mode has no backend metadata to carry.
        self.assertNotIn("metadata", intent_obj)
        self.assertNotIn("steps", intent_obj)
        self.assertNotIn("fill_form", intent_obj)

    def test_emit_intent_has_no_selector_fields(self):
        result = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run")
        text = json.dumps(result.data["intent"]).lower()
        for forbidden in ("selector", "input[", "button[", "xpath", "fill_form"):
            self.assertNotIn(forbidden, text,
                             f"intent must not carry the {forbidden!r} selector hint")

    def test_emit_intent_confirmed_flips_policy(self):
        confirmed = run_cli(self.mem, self.profile_path, "--emit-intent",
                            "--dry-run", "--confirmed")
        self.assertFalse(
            confirmed.data["intent"]["policy"]["require_confirmation_before_final_submit"])
        auto = run_cli(self.mem, self.profile_path, "--emit-intent",
                       "--dry-run", "--auto-apply")
        self.assertFalse(
            auto.data["intent"]["policy"]["require_confirmation_before_final_submit"])
        # The hard gates never change.
        plain = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run")
        self.assertTrue(plain.data["intent"]["policy"]["never_fill_credentials"])
        self.assertTrue(plain.data["intent"]["policy"]["stop_on_auth_url"])

    def test_emit_intent_max_steps_flag(self):
        default = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run")
        self.assertEqual(default.data["intent"]["policy"]["max_steps"], 25)
        tuned = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run",
                        "--max-steps", "10")
        self.assertEqual(tuned.data["intent"]["policy"]["max_steps"], 10)

    def test_emit_intent_never_writes_records(self):
        # Even a non-dry-run intent (browser mock reached) must not write the
        # applied record — verdict.py is the sole writer. --confirmed is the
        # strongest case: approved by the human, still no planner write.
        result = run_cli(self.mem, self.profile_path, "--emit-intent",
                         "--confirmed")
        self.assertEqual(result.code, 0)
        self.assertFalse(self.record_path.exists())

    def test_emit_intent_refusal_still_blocks(self):
        profile = dict(VALID_PROFILE, no_apply=True)
        path = write_profile(self.mem, profile)
        result = run_cli(self.mem, path, "--emit-intent", "--dry-run")
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "refusal_draft_blocked")

    def test_emit_intent_already_applied_still_blocks(self):
        self.record_path.parent.mkdir(parents=True, exist_ok=True)
        self.record_path.write_text(
            json.dumps({"job_id": JOB_ID, "status": "applied"}), encoding="utf-8"
        )
        result = run_cli(self.mem, self.profile_path, "--emit-intent", "--dry-run")
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "already_applied")

    def test_build_intent_carries_metadata(self):
        intent_obj = intent.build_intent(
            intent_id="test-uuid",
            job_id=JOB_ID,
            job_url=GUPY_URL,
            portal="gupy",
            profile=VALID_PROFILE,
            require_confirmation=True,
            job_title="Back-end Developer Jr",
            job_company="ACME Tech",
            backend_job_id=7,
            api_base_url="http://localhost:8080",
        )
        self.assertEqual(intent_obj["metadata"]["job_title"], "Back-end Developer Jr")
        self.assertEqual(intent_obj["metadata"]["job_company"], "ACME Tech")
        self.assertEqual(intent_obj["metadata"]["backend_job_id"], 7)
        self.assertEqual(intent_obj["metadata"]["api_base_url"], "http://localhost:8080")
        self.assertNotIn("dry_run", intent_obj)

    def test_emit_intent_api_mode_carries_metadata(self):
        # API-first mode (--job-id) prefills title/company/backend_id into the
        # intent metadata (issue #46 bridge into the mcp-apply-loop intent).
        with redirect_stdout(io.StringIO()) as buf:
            with unittest.mock.patch("job_api.resolve_token", return_value="tok"), \
                    unittest.mock.patch("job_api.api_get_job", return_value={
                        "id": 7,
                        "url": GUPY_URL,
                        "title": "Back-end Developer Jr",
                        "company": "ACME Tech",
                    }):
                code = apply.run([
                    "--profile", str(self.profile_path),
                    "--memory-dir", str(self.mem),
                    "--job-id", "7",
                    "--api-base-url", "http://localhost:8080",
                    "--emit-intent",
                    "--dry-run",
                ])
        self.assertEqual(code, 0)
        data = json.loads(buf.getvalue())
        self.assertEqual(data["intent"]["metadata"]["job_title"], "Back-end Developer Jr")
        self.assertEqual(data["intent"]["metadata"]["job_company"], "ACME Tech")
        self.assertEqual(data["intent"]["metadata"]["backend_job_id"], 7)
        self.assertEqual(data["intent"]["metadata"]["api_base_url"], "http://localhost:8080")

    def test_classic_job_url_flow_emits_intent(self):
        # Cutover pkg 3 (spec l.291-299): the legacy selector-based action plan
        # is GONE — the classic --job-url flow now emits the same intent
        # envelope as --emit-intent.
        result = run_cli(self.mem, self.profile_path, "--dry-run")
        self.assertEqual(result.code, 0)
        self.assertIn("intent", result.data)
        self.assertIn("intent_id", result.data["intent"])
        self.assertNotIn("steps", result.data)


# ---------------------------------------------------------------------------
# Preflight gate (issue #72) — deterministic pre-intent gate
# ---------------------------------------------------------------------------

class _FakePreflight:
    """preflight stand-in injected as ``apply.preflight`` in the gate tests.

    PR #80 review P1 — apply.py consumes the DIRECT ``evaluate(config_path)``
    dict API (no argv, no stdout sniffing). The fake records every config path
    it is evaluated with and returns the configured (exit_code, payload) tuple.
    """

    def __init__(self, payload, code):
        self.payload = payload
        self.code = code
        self.calls = []

    def evaluate(self, config_path):
        self.calls.append(config_path)
        return self.code, self.payload


class PreflightGateTests(unittest.TestCase):
    """Issue #72 — the deterministic preflight gate: apply.py refuses to emit
    an intent when --preflight-config reports a failing check (fail-closed,
    §3.4 — reported VERBATIM, no improvisation, no fallback browsers)."""

    PREFLIGHT_FAIL = {
        "ok": False,
        "check": "chromium_running",
        "error": "chromium_not_running",
        "detail": "no Chromium process is running for the dedicated profile",
        "checks": {"chromium_running": {"ok": False,
                                        "error": "chromium_not_running"}},
    }

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)
        self.config_path = str(self.mem / "preflight-config.json")

    def _run_gate(self, payload, code, *extra):
        """Run apply.run in-process with the fake preflight injected; return
        (exit_code, stdout). --dry-run keeps the run off the browser/session."""
        fake = _FakePreflight(payload, code)
        with unittest.mock.patch("apply.preflight", fake, create=True):
            buf = io.StringIO()
            with redirect_stdout(buf):
                exit_code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                    "--dry-run",
                    "--preflight-config", self.config_path,
                    *extra,
                ])
        return exit_code, fake, buf.getvalue()

    def test_parse_args_has_preflight_config(self):
        args = apply.parse_args(["--preflight-config", "preflight.json"])
        self.assertEqual(args.preflight_config, "preflight.json")

    def test_preflight_failure_blocks_intent_verbatim(self):
        code, fake, out = self._run_gate(self.PREFLIGHT_FAIL, 1)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out), self.PREFLIGHT_FAIL)
        self.assertNotIn("intent", out)
        self.assertEqual(fake.calls, [self.config_path])

    def test_preflight_pass_allows_intent(self):
        code, _, out = self._run_gate({"ok": True, "checks": {}}, 0)
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertIn("intent", data)
        self.assertNotIn("error", data)

    def test_preflight_config_error_refuses_intent(self):
        code, _, out = self._run_gate(
            {"error": "invalid_config",
             "detail": "config.browser_tool.tabs must be a list"}, 2)
        self.assertEqual(code, 2)
        data = json.loads(out)
        self.assertEqual(data["error"], "invalid_config")
        self.assertNotIn("intent", out)

    def test_preflight_called_with_config_path(self):
        fake = _FakePreflight({"ok": True, "checks": {}}, 0)
        with unittest.mock.patch("apply.preflight", fake, create=True):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                    "--dry-run",
                    "--preflight-config", self.config_path,
                ])
        self.assertEqual(code, 0)
        self.assertEqual(fake.calls, [self.config_path])

    def test_malformed_pass_payload_fails_closed(self):
        # A preflight that exits 0 but prints {"ok": false} (or garbage)
        # must NEVER let the intent through — fail-closed by payload too.
        code, _, out = self._run_gate(
            {"ok": False, "error": "browser_tool_detached",
             "detail": "tool not attached"}, 0)
        self.assertEqual(code, 1)
        self.assertNotIn("intent", out)

    def test_dry_run_without_config_keeps_default_gate_dormant(self):
        # PR #80 review P0-1 — the gate is DEFAULT-ON on real runs, but a
        # --dry-run WITHOUT an explicit --preflight-config keeps it dormant
        # (dry runs plan the intent without the preflight).
        fake = _FakePreflight(self.PREFLIGHT_FAIL, 1)
        with unittest.mock.patch("apply.preflight", fake, create=True):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                    "--dry-run",
                ])
        self.assertEqual(fake.calls, [])
        self.assertEqual(code, 0)
        data = json.loads(buf.getvalue())
        self.assertIn("intent", data)

    def test_real_run_without_flag_runs_gate_on_default_path(self):
        # PR #80 review P0-1 — a REAL run with no flags evaluates the gate on
        # the memory-dir default config; a pass lets the intent through.
        fake = _FakePreflight({"ok": True, "checks": {}}, 0)
        with unittest.mock.patch("apply.preflight", fake, create=True), \
                unittest.mock.patch.object(
                    apply, "ensure_browser",
                    return_value={"status": "ready"}), \
                unittest.mock.patch.object(
                    apply, "verify_session",
                    return_value={"session": "active"}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                ])
        self.assertEqual(code, 0)
        default_config = str(self.mem / apply.DEFAULT_PREFLIGHT_CONFIG)
        self.assertEqual(fake.calls, [default_config])
        data = json.loads(buf.getvalue())
        self.assertIn("intent", data)

    def test_skip_preflight_check_opts_out_on_real_run(self):
        # PR #80 review P0-1 — --skip-preflight-check is the documented opt-out
        # for real runs; the default gate does not evaluate.
        fake = _FakePreflight(self.PREFLIGHT_FAIL, 1)
        with unittest.mock.patch("apply.preflight", fake, create=True), \
                unittest.mock.patch.object(
                    apply, "ensure_browser",
                    return_value={"status": "ready"}), \
                unittest.mock.patch.object(
                    apply, "verify_session",
                    return_value={"session": "active"}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                    "--skip-preflight-check",
                ])
        self.assertEqual(fake.calls, [])
        self.assertEqual(code, 0)
        data = json.loads(buf.getvalue())
        self.assertIn("intent", data)

    def test_real_run_without_flags_missing_default_config_fails_closed(self):
        # PR #80 review P0-1 — fail-closed semantics: on a real run the default
        # config path is REQUIRED; a missing file relays the invalid_config
        # exit — never a silent "gate skipped".
        config_err = {"ok": False, "error": "invalid_config",
                      "detail": "config file not found: default path"}
        fake = _FakePreflight(config_err, 2)
        with unittest.mock.patch("apply.preflight", fake, create=True), \
                unittest.mock.patch.object(
                    apply, "ensure_browser",
                    return_value={"status": "ready"}), \
                unittest.mock.patch.object(
                    apply, "verify_session",
                    return_value={"session": "active"}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = apply.run([
                    "--job-url", GUPY_URL,
                    "--profile", self.profile_path,
                    "--memory-dir", str(self.mem),
                    "--portal", "gupy",
                ])
        self.assertEqual(code, 2)
        data = json.loads(buf.getvalue())
        self.assertEqual(data["error"], "invalid_config")
        self.assertNotIn("intent", buf.getvalue())


# ---------------------------------------------------------------------------
# Session expiry gate (issue #41) — pre-flight for every emission mode
# ---------------------------------------------------------------------------

class SessionGateTests(unittest.TestCase):
    """An expired session (browser on a login/auth page) blocks the intent."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def test_expired_session_blocks_intent(self):
        result = run_cli(
            self.mem, self.profile_path,
            "--current-url", "https://portal.gupy.io/login",
        )
        self.assertEqual(result.code, 0)
        self.assertEqual(result.data["error"], "session_expired")
        self.assertIn("login_url", result.data)
        self.assertEqual(result.data["login_url"], GUPY_URL)
        self.assertNotIn("intent", result.data)

    def test_skip_session_check_emits_intent(self):
        result = run_cli(
            self.mem, self.profile_path,
            "--current-url", "https://portal.gupy.io/login",
            "--skip-session-check", "--dry-run",
        )
        self.assertEqual(result.code, 0)
        self.assertIn("intent", result.data)

    def test_dry_run_skips_session_check(self):
        # Legacy behavior kept: --dry-run implies the browser/session is never
        # consulted, so an expired-looking --current-url does not block it.
        result = run_cli(
            self.mem, self.profile_path,
            "--current-url", "https://portal.gupy.io/login",
            "--dry-run",
        )
        self.assertEqual(result.code, 0)
        self.assertIn("intent", result.data)


if __name__ == "__main__":
    unittest.main()