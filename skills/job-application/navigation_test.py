#!/usr/bin/env python3
"""
navigation_test.py — issue #38 tests for the navigation auth/loop guard.

Covers is_auth_url() and NavigationGuard (loop detection + structured results).
Plain unittest, no external dependencies.
"""

import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import navigation  # noqa: E402

APPLY_PATH = Path(__file__).resolve().parent / "apply.py"
GUPY_URL = "https://jobs.gupy.io/jobs/12345-desenvolvedor-java"
JOB_ID = "12345-desenvolvedor-java"

VALID_PROFILE = {
    "name": "Juan Antonio Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 42 99833-1363",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga de desenvolvedor.",
}


def write_profile(memory_dir, profile=None):
    path = Path(memory_dir) / "profile.json"
    path.write_text(json.dumps(profile if profile is not None else VALID_PROFILE),
                    encoding="utf-8")
    return str(path)


class _MockCdpHandler(http.server.BaseHTTPRequestHandler):
    """Minimal fake CDP endpoint: responds 200 to /json/version.

    Lets CLI subprocess tests satisfy the issue #39 browser-recovery check
    without a real browser or network.
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


# apply.py's browser-recovery check (issue #39) targets this mock CDP endpoint so
# no real Chromium or CDP traffic is ever involved during tests.
MOCK_CDP_SERVER, MOCK_CDP_PORT = _start_mock_cdp()
MOCK_CDP_URL = f"http://127.0.0.1:{MOCK_CDP_PORT}"


# ---------------------------------------------------------------------------
# is_auth_url
# ---------------------------------------------------------------------------

class IsAuthUrlTests(unittest.TestCase):
    """Auth detection for login/auth/signin/candidates-auth paths."""

    def test_candidates_auth_path_detected(self):
        self.assertTrue(
            navigation.is_auth_url("https://jobs.gupy.io/candidates/auth")
        )

    def test_login_path_detected(self):
        self.assertTrue(
            navigation.is_auth_url("https://jobs.gupy.io/login?return=/jobs/123")
        )

    def test_signin_path_detected(self):
        self.assertTrue(navigation.is_auth_url("https://jobs.gupy.io/signin"))

    def test_auth_segment_detected(self):
        self.assertTrue(navigation.is_auth_url("https://jobs.gupy.io/account/auth"))

    def test_shallow_containing_word_not_false_positive(self):
        # "authentication" contains "auth" as a word — check only segment boundaries.
        self.assertFalse(navigation.is_auth_url("https://jobs.gupy.io/authentication-info"))

    def test_normal_job_url_not_auth(self):
        self.assertFalse(navigation.is_auth_url(GUPY_URL))

    def test_homepage_not_auth(self):
        self.assertFalse(navigation.is_auth_url("https://jobs.gupy.io"))

    def test_case_insensitive(self):
        self.assertTrue(navigation.is_auth_url("https://jobs.gupy.io/LOGIN"))

    def test_invalid_url_not_auth(self):
        self.assertFalse(navigation.is_auth_url("not a url"))


# ---------------------------------------------------------------------------
# normalize_url
# ---------------------------------------------------------------------------

class NormalizeUrlTests(unittest.TestCase):
    """Normalized comparison strips query/fragment and lowercases host."""

    def test_strips_query(self):
        self.assertEqual(
            navigation.normalize_url("https://jobs.gupy.io/jobs/123?ref=abc"),
            "https://jobs.gupy.io/jobs/123",
        )

    def test_strips_fragment(self):
        self.assertEqual(
            navigation.normalize_url("https://jobs.gupy.io/jobs/123#top"),
            "https://jobs.gupy.io/jobs/123",
        )

    def test_lowercases_host(self):
        self.assertEqual(
            navigation.normalize_url("https://JOBS.Gupy.io/jobs/123"),
            "https://jobs.gupy.io/jobs/123",
        )

    def test_strips_trailing_slash(self):
        self.assertEqual(
            navigation.normalize_url("https://jobs.gupy.io/jobs/123/"),
            "https://jobs.gupy.io/jobs/123",
        )

    def test_equal_urls_normalize_same(self):
        a = navigation.normalize_url("https://jobs.gupy.io/jobs/123?x=1")
        b = navigation.normalize_url("https://jobs.gupy.io/jobs/123")
        self.assertEqual(a, b)


# ---------------------------------------------------------------------------
# verify_session (issue #41)
# ---------------------------------------------------------------------------

class VerifySessionTests(unittest.TestCase):
    """verify_session: active/expired detection + login_url precedence.

    A session counts as expired when the current URL is a login/auth/signin
    page (reusing is_auth_url). The result is pure — no browser, no state.
    """

    def test_normal_url_is_active(self):
        result = navigation.verify_session(GUPY_URL)
        self.assertEqual(result["session"], "active")

    def test_none_current_url_is_active(self):
        result = navigation.verify_session(None)
        self.assertEqual(result["session"], "active")

    def test_empty_current_url_is_active(self):
        result = navigation.verify_session("")
        self.assertEqual(result["session"], "active")

    def test_current_url_equal_to_hint_is_active(self):
        result = navigation.verify_session(GUPY_URL, login_hint_url=GUPY_URL)
        self.assertEqual(result["session"], "active")

    def test_login_page_is_expired(self):
        result = navigation.verify_session("https://jobs.gupy.io/login")
        self.assertEqual(result["session"], "expired")

    def test_auth_path_expired(self):
        result = navigation.verify_session("https://jobs.gupy.io/candidates/auth")
        self.assertEqual(result["session"], "expired")

    def test_signin_path_expired(self):
        result = navigation.verify_session("https://jobs.gupy.io/signin")
        self.assertEqual(result["session"], "expired")

    def test_expired_detail_is_ptbr_constant(self):
        result = navigation.verify_session("https://jobs.gupy.io/login")
        self.assertEqual(result["detail"], "Sessão expirada. Faça login no Gupy e digite confirmar.")

    def test_expired_detail_references_named_constant(self):
        self.assertEqual(
            navigation.SESSION_EXPIRED_DETAIL,
            "Sessão expirada. Faça login no Gupy e digite confirmar.",
        )

    def test_login_url_uses_hint_precedence(self):
        login = "https://jobs.gupy.io/login"
        result = navigation.verify_session(login, login_hint_url=GUPY_URL)
        self.assertEqual(result["login_url"], GUPY_URL)

    def test_login_url_falls_back_to_current_url(self):
        login = "https://jobs.gupy.io/signin"
        result = navigation.verify_session(login)
        self.assertEqual(result["login_url"], login)

    def test_expired_result_exact_keys(self):
        result = navigation.verify_session(
            "https://jobs.gupy.io/login", login_hint_url=GUPY_URL
        )
        self.assertEqual(set(result.keys()), {"session", "detail", "login_url"})

    def test_active_result_has_no_login_keys(self):
        result = navigation.verify_session(GUPY_URL, login_hint_url=GUPY_URL)
        self.assertEqual(set(result.keys()), {"session"})


# ---------------------------------------------------------------------------
# NavigationGuard
# ---------------------------------------------------------------------------

class NavigationGuardTests(unittest.TestCase):
    """Loop detection + structured result."""

    def test_first_visit_no_loop_no_auth(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        result = guard.record(GUPY_URL)
        self.assertFalse(result["loop_detected"])
        self.assertFalse(result["auth_detected"])
        self.assertEqual(result["visits"], 1)

    def test_second_visit_no_loop(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        guard.record(GUPY_URL)
        result = guard.record(GUPY_URL)
        self.assertFalse(result["loop_detected"])
        self.assertEqual(result["visits"], 2)

    def test_third_visit_loop_detected(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        guard.record(GUPY_URL)
        guard.record(GUPY_URL)
        result = guard.record(GUPY_URL)
        self.assertTrue(result["loop_detected"])
        self.assertEqual(result["visits"], 3)

    def test_auth_url_immediately_detected(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        result = guard.record("https://jobs.gupy.io/candidates/auth")
        self.assertTrue(result["auth_detected"])
        self.assertFalse(result["loop_detected"])

    def test_result_carries_manual_url(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        result = guard.record(GUPY_URL)
        self.assertEqual(result["manual_url"], GUPY_URL)

    def test_normalized_loop_detection_ignores_query(self):
        guard = navigation.NavigationGuard(job_url=GUPY_URL)
        guard.record(GUPY_URL + "?step=1")
        guard.record(GUPY_URL + "?step=2")
        result = guard.record(GUPY_URL)
        self.assertTrue(result["loop_detected"])

    def test_seeded_with_visited_urls(self):
        guard = navigation.NavigationGuard(
            job_url=GUPY_URL, visited_urls=[GUPY_URL, GUPY_URL]
        )
        result = guard.record(GUPY_URL)
        self.assertTrue(result["loop_detected"])

    def test_auth_checked_before_counting(self):
        # Seeded with 3 visits but the next URL is an auth URL — auth wins.
        guard = navigation.NavigationGuard(
            job_url=GUPY_URL, visited_urls=[GUPY_URL, GUPY_URL, GUPY_URL]
        )
        result = guard.record("https://jobs.gupy.io/login")
        self.assertTrue(result["auth_detected"])


# ---------------------------------------------------------------------------
# apply.py integration
# ---------------------------------------------------------------------------

class ApplyIntegrationTests(unittest.TestCase):
    """apply.py surfaces the session expiry gate (#41) via --current-url and
    keeps accepting the legacy #38 flags (--visited-urls) without interference
    (cutover pkg 3: the deterministic auth/loop guard is retired for the loop —
    the executor enforces those stops at runtime)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def run_cli(self, *args, url=GUPY_URL, cdp_url=MOCK_CDP_URL):
        cmd = [
            sys.executable, str(APPLY_PATH),
            "--job-url", url,
            "--portal", "gupy",
            "--memory-dir", str(self.mem),
            "--profile", self.profile_path,
        ]
        if cdp_url:
            cmd += ["--cdp-url", cdp_url]
        cmd.extend(args)
        proc = subprocess.run(cmd, capture_output=True, text=True)
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError:
            data = {"raw_stdout": proc.stdout}
        return proc.returncode, data

    def test_auth_current_url_yields_session_expired_error(self):
        # Issue #41 — pre-flight gate (cutover pkg 3): a login page stops the
        # planner with the session_expired error (exit 0 — ask the human to
        # authenticate); no intent is emitted.
        code, data = self.run_cli("--current-url", "https://jobs.gupy.io/login")
        self.assertEqual(code, 0)
        self.assertEqual(data["error"], "session_expired")
        self.assertIn("login_url", data)
        self.assertNotIn("intent", data)

    def test_no_guard_flags_emits_intent(self):
        code, data = self.run_cli()
        self.assertEqual(code, 0)
        self.assertIn("intent", data)
        self.assertNotIn("steps", data)

    def test_legacy_flags_still_accepted(self):
        # The old #38 flags stay accepted (verdict-input / executor flags); they
        # no longer trigger auth_required / navigation_loop errors.
        code, data = self.run_cli(
            "--current-url", GUPY_URL, "--visited-urls", GUPY_URL
        )
        self.assertEqual(code, 0)
        self.assertIn("intent", data)


if __name__ == "__main__":
    unittest.main()
