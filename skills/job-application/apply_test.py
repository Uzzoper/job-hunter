#!/usr/bin/env python3
"""
apply_test.py — issue #37 tests for apply.py (structured Gupy application planning).

Plain unittest (pytest-compatible). apply.py is pure orchestration: it never
touches a browser or the network, so CLI-level tests run via subprocess and the
YAML mini-parser/helpers are tested by direct import.

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

APPLY_PATH = Path(__file__).resolve().parent / "apply.py"
PORTAL = "gupy"
GUPY_URL = "https://jobs.gupy.io/jobs/12345-desenvolvedor-java"
JOB_ID = "12345-desenvolvedor-java"

VALID_PROFILE = {
    "name": "Juan Antonio Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 42 99833-1363",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga de desenvolvedor.",
}


class _MockCdpHandler(http.server.BaseHTTPRequestHandler):
    """Minimal fake CDP endpoint: responds 200 to /json/version.

    Lets CLI subprocess tests exercise the issue #39 browser-recovery path
    without a real browser: ensure_browser() sees CDP as reachable and reports
    status "ready", so the action plan is produced normally.
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
    browser check reports "ready" and the action plan path is reached. Pass
    ``cdp_url=None`` (or an explicit ``--cdp-url`` in *args) to override.
    """
    cmd = [
        sys.executable, str(APPLY_PATH),
        "--job-url", url,
        "--portal", portal,
        "--memory-dir", str(memory_dir),
        "--profile", profile_path,
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
# YAML mini-parser
# ---------------------------------------------------------------------------

class YamlParserTests(unittest.TestCase):
    """parse_yaml_flat / nest_dotted robustness."""

    def test_flat_key_value(self):
        d = apply.parse_yaml_flat("a: 1\nb: hello\n")
        self.assertEqual(d, {"a": "1", "b": "hello"})

    def test_quoted_value_keeps_colon(self):
        d = apply.parse_yaml_flat('k: "v: with colon"\n')
        self.assertEqual(d["k"], "v: with colon")

    def test_unquoted_url_value(self):
        d = apply.parse_yaml_flat("url: https://a.com/x\n")
        self.assertEqual(d["url"], "https://a.com/x")

    def test_trailing_comment_stripped(self):
        d = apply.parse_yaml_flat("a: value # comment\n")
        self.assertEqual(d["a"], "value")

    def test_comment_line_skipped(self):
        d = apply.parse_yaml_flat("# full comment\na: 1\n")
        self.assertNotIn("#", d)
        self.assertEqual(d["a"], "1")

    def test_escaped_quotes_unescaped(self):
        d = apply.parse_yaml_flat('k: "say \\"hi\\""\n')
        self.assertEqual(d["k"], 'say "hi"')

    def test_single_quoted_value_keeps_colon(self):
        d = apply.parse_yaml_flat("k: 'a: b'\n")
        self.assertEqual(d["k"], "a: b")

    def test_dotted_key_nesting(self):
        d = apply.nest_dotted(
            {"field.name.selector": "x", "field.name.type": "fill"}
        )
        self.assertEqual(d["field"]["name"]["selector"], "x")
        self.assertEqual(d["field"]["name"]["type"], "fill")

    def test_load_gupy_portal(self):
        cfg = apply.load_portal("gupy")
        self.assertIsNotNone(cfg)
        self.assertEqual(cfg["portal"], "gupy")
        fields = apply.portal_fields(cfg)
        names = [f["name"] for f in fields]
        self.assertEqual(
            names,
            ["name", "email", "phone", "cv_upload", "cover_letter", "submit"],
        )
        by_name = {f["name"]: f for f in fields}
        self.assertEqual(by_name["name"]["selector"], "input[name='name']")
        self.assertEqual(by_name["cv_upload"]["type"], "upload")
        self.assertEqual(by_name["cover_letter"]["source"], "cover_text")
        self.assertEqual(
            cfg.get("form_url_pattern"),
            "https://jobs.gupy.io/jobs/{job_id}",
        )
        self.assertEqual(cfg.get("cdp_url"), "http://localhost:9222")


# ---------------------------------------------------------------------------
# Portal loading
# ---------------------------------------------------------------------------

class PortalTests(unittest.TestCase):
    """Unknown portals must error cleanly."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def test_unknown_portal_errors_cleanly(self):
        result = run_cli(self.mem, self.profile_path, portal="linkedin")
        self.assertEqual(result.code, 1)
        self.assertEqual(result.data["error"], "unknown_portal")
        self.assertIn("linkedin", result.data["detail"])

    def test_load_portal_returns_none_for_unknown(self):
        self.assertIsNone(apply.load_portal("linkedin"))
        self.assertIsNone(apply.load_portal(""))


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
        result = run_cli(self.mem, path)
        self.assertEqual(result.code, 0)
        self.assertTrue(result.data["ok"])
        self.assertNotIn("error", result.data)


# ---------------------------------------------------------------------------
# Confirm gate
# ---------------------------------------------------------------------------

class ConfirmGateTests(unittest.TestCase):
    """No submit step without --confirmed; plan stops at confirm_checkpoint."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def step_types(self, result):
        return [s["type"] for s in result.data["steps"]]

    def test_without_confirmed_has_no_submit(self):
        result = run_cli(self.mem, self.profile_path)
        self.assertEqual(result.code, 0)
        types = self.step_types(result)
        self.assertNotIn("submit", types)
        self.assertIn("confirm_checkpoint", types)
        self.assertIn("screenshot", types)
        # The unconfirmed plan must stop at the confirmation checkpoint.
        self.assertEqual(types[-1], "confirm_checkpoint")
        self.assertTrue(result.data["confirmationRequired"])

    def test_with_confirmed_has_submit_step(self):
        result = run_cli(self.mem, self.profile_path, "--confirmed")
        self.assertEqual(result.code, 0)
        types = self.step_types(result)
        self.assertEqual(types.count("submit"), 1)
        self.assertEqual(types[-1], "submit")
        self.assertFalse(result.data["confirmationRequired"])

    def test_submit_step_uses_yaml_selector(self):
        result = run_cli(self.mem, self.profile_path, "--confirmed")
        submit = next(s for s in result.data["steps"] if s["type"] == "submit")
        self.assertEqual(submit["selector"], "button[type='submit']")


# ---------------------------------------------------------------------------
# Action-plan content
# ---------------------------------------------------------------------------

class PlanContentTests(unittest.TestCase):
    """The emitted plan carries selectors, values, and job metadata."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)
        self.result = run_cli(self.mem, self.profile_path)

    def test_plan_ok_and_portal(self):
        self.assertTrue(self.result.data["ok"])
        self.assertEqual(self.result.data["portal"], "gupy")
        self.assertEqual(self.result.data["jobId"], JOB_ID)
        self.assertEqual(self.result.data["jobUrl"], GUPY_URL)

    def test_fill_upload_order(self):
        steps = self.result.data["steps"]
        field_steps = [s for s in steps if "field" in s]
        self.assertEqual(
            [s["field"] for s in field_steps],
            ["name", "email", "phone", "cv_upload", "cover_letter"],
        )
        self.assertEqual(field_steps[0]["type"], "fill")
        self.assertEqual(field_steps[3]["type"], "upload")

    def test_steps_carry_selectors_and_profile_values(self):
        steps = self.result.data["steps"]
        by_field = {s["field"]: s for s in steps if "field" in s}
        self.assertEqual(by_field["name"]["selector"], "input[name='name']")
        self.assertEqual(by_field["name"]["value"], VALID_PROFILE["name"])
        self.assertEqual(by_field["email"]["value"], VALID_PROFILE["email"])
        self.assertEqual(by_field["cv_upload"]["value"], VALID_PROFILE["cv_path"])
        self.assertEqual(
            by_field["cover_letter"]["value"], VALID_PROFILE["cover_text"]
        )

    def test_screenshot_path_lives_under_memory_dir(self):
        steps = self.result.data["steps"]
        shot = next(s for s in steps if s["type"] == "screenshot")
        self.assertTrue(shot["path"].startswith(str(self.mem)))
        self.assertTrue(shot["path"].endswith(f"{JOB_ID}.png"))

    def test_form_url_pattern_expanded(self):
        self.assertEqual(
            self.result.data["form_url"],
            "https://jobs.gupy.io/jobs/12345-desenvolvedor-java",
        )


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

    def test_cli_record_round_trip_then_blocked(self):
        first = run_cli(self.mem, self.profile_path, "--record-applied")
        self.assertEqual(first.code, 0)
        self.assertTrue(self.record_path.is_file())
        record = json.loads(self.record_path.read_text(encoding="utf-8"))
        self.assertEqual(record["status"], "applied")
        self.assertEqual(record["job_id"], JOB_ID)
        self.assertEqual(record["contact_email"], VALID_PROFILE["email"])
        self.assertEqual(record["portal"], "gupy")
        self.assertIn("applied_at", record)
        self.assertIn("screenshot_path", record)

        second = run_cli(self.mem, self.profile_path)
        self.assertEqual(second.code, 1)
        self.assertEqual(second.data["error"], "already_applied")

    def test_confirmed_run_writes_record(self):
        run_cli(self.mem, self.profile_path, "--confirmed")
        self.assertTrue(self.record_path.is_file())

    def test_dry_run_writes_nothing(self):
        run_cli(self.mem, self.profile_path, "--record-applied", "--dry-run")
        self.assertFalse(self.record_path.exists())
        dry = run_cli(self.mem, self.profile_path, "--confirmed", "--dry-run")
        self.assertTrue(dry.data["ok"])
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

    def test_record_written_by_module_round_trips(self):
        rec = apply.write_applied_record(
            self.mem, "abc-xyz", VALID_PROFILE, "gupy",
            str(self.mem / "screenshots" / "abc-xyz.png"),
        )
        self.assertEqual(rec["status"], "applied")
        self.assertEqual(rec["contact_email"], VALID_PROFILE["email"])
        loaded = apply.check_idempotency(self.mem, "abc-xyz")
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["status"], "applied")
        self.assertEqual(loaded["job_id"], "abc-xyz")


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
        self.assertIsNone(args.portal)
        self.assertIsNone(args.memory_dir)
        self.assertFalse(args.dry_run)
        self.assertFalse(args.confirmed)
        self.assertFalse(args.record_applied)
        self.assertIsNone(args.cdp_url)
        self.assertIsNone(args.user_data_dir)
        self.assertFalse(args.check_browser)


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
            self.assertTrue(result.data["ok"])
            self.assertTrue(result.data["dryRun"])


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


if __name__ == "__main__":
    unittest.main()