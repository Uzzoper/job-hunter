#!/usr/bin/env python3
"""
navigation_test.py — issue #38 tests for the navigation auth/loop guard.

Covers is_auth_url() and NavigationGuard (loop detection + structured results).
Plain unittest, no external dependencies.
"""

import json
import os
import subprocess
import sys
import tempfile
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
    """apply.py surfaces auth_required / navigation_loop via --current-url and
    --visited-urls, keeping the planner stateless."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mem = Path(self.tmp.name)
        self.profile_path = write_profile(self.mem)

    def run_cli(self, *args, url=GUPY_URL):
        cmd = [
            sys.executable, str(APPLY_PATH),
            "--job-url", url,
            "--portal", "gupy",
            "--memory-dir", str(self.mem),
            "--profile", self.profile_path,
        ]
        cmd.extend(args)
        proc = subprocess.run(cmd, capture_output=True, text=True)
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError:
            data = {"raw_stdout": proc.stdout}
        return proc.returncode, data

    def test_auth_required_error(self):
        code, data = self.run_cli("--current-url", "https://jobs.gupy.io/login")
        self.assertEqual(code, 1)
        self.assertEqual(data["error"], "auth_required")
        self.assertIn("manual_url", data)
        self.assertEqual(data["manual_url"], GUPY_URL)
        self.assertIn("screenshot_path", data)

    def test_navigation_loop_error(self):
        code, data = self.run_cli(
            "--visited-urls", ",".join([GUPY_URL, GUPY_URL, GUPY_URL])
        )
        self.assertEqual(code, 1)
        self.assertEqual(data["error"], "navigation_loop")
        self.assertIn("manual_url", data)
        self.assertEqual(data["manual_url"], GUPY_URL)
        self.assertIn("screenshot_path", data)

    def test_two_visits_is_not_a_loop(self):
        code, data = self.run_cli(
            "--visited-urls", ",".join([GUPY_URL, GUPY_URL])
        )
        self.assertEqual(code, 0)
        self.assertTrue(data["ok"])

    def test_no_guard_flags_is_normal_plan(self):
        code, data = self.run_cli()
        self.assertEqual(code, 0)
        self.assertTrue(data["ok"])

    def test_partial_failure_of_old_flags_still_works(self):
        # Adding the new flags must not disturb the existing confirm/record flow.
        code, data = self.run_cli(
            "--current-url", GUPY_URL, "--visited-urls", GUPY_URL
        )
        self.assertEqual(code, 0)
        self.assertTrue(data["ok"])


if __name__ == "__main__":
    unittest.main()
