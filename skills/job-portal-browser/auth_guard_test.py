#!/usr/bin/env python3
"""
auth_guard_test.py — issue #40 tests for auth_guard.py (auth-page detection +
stop for the job-portal-browser skill).

Plain unittest. auth_guard is pure stdlib (re, urllib.parse, typing), so all
tests run directly against the module without a browser or network.

Run:
    python3 auth_guard_test.py
    python3 -m unittest auth_guard_test -v
"""

import json
import os
import sys
import unittest

# Allow direct import when running from the skill dir.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import auth_guard  # noqa: E402  (RED phase: module does not exist yet)


class IsAuthUrlTests(unittest.TestCase):
    """is_auth_url boundary-checked path-segment detection."""

    def test_login_segment_detected(self):
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/login")
        )

    def test_signin_segment_detected(self):
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/signin")
        )

    def test_auth_segment_detected(self):
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/auth")
        )

    def test_sign_in_segment_detected(self):
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/sign-in")
        )

    def test_candidates_auth_detected(self):
        # Gupy candidates area: /candidates/auth/<...> fires on the exact
        # "auth" path segment.
        self.assertTrue(
            auth_guard.is_auth_url("https://jobs.gupy.io/candidates/auth/login")
        )
        self.assertTrue(
            auth_guard.is_auth_url("https://jobs.gupy.io/candidates/auth/")
        )

    def test_nested_auth_segment_detected(self):
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/account/login")
        )

    def test_case_insensitive(self):
        self.assertTrue(auth_guard.is_auth_url("https://portal.example.com/Login"))
        self.assertTrue(auth_guard.is_auth_url("https://portal.example.com/AUTH"))
        self.assertTrue(auth_guard.is_auth_url("https://portal.example.com/SIGNIN"))

    def test_query_string_ignored(self):
        # An auth-looking value in the query string must NOT trigger detection...
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/jobs?next=/login")
        )
        # ...while an auth segment in the path wins regardless of the query.
        self.assertTrue(
            auth_guard.is_auth_url("https://portal.example.com/login?next=/jobs")
        )

    def test_fragment_ignored(self):
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/jobs#login")
        )

    def test_lookalike_full_segment_not_detected(self):
        # Boundary rule: only WHOLE path segments match. "authentication-page"
        # is a single segment containing "auth" but not equal to it.
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/authentication-page")
        )
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/authentication")
        )

    def test_signin_v2_lookalike_not_detected(self):
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/signin-v2")
        )

    def test_job_slug_lookalike_not_detected(self):
        # A job slug like "authentication-specialist" must not false-positive.
        self.assertFalse(
            auth_guard.is_auth_url(
                "https://jobs.gupy.io/jobs/123-authentication-specialist"
            )
        )

    def test_plain_job_url_not_detected(self):
        self.assertFalse(
            auth_guard.is_auth_url("https://jobs.gupy.io/jobs/123-desenvolvedor")
        )
        self.assertFalse(
            auth_guard.is_auth_url("https://portal.example.com/careers")
        )

    def test_none_and_empty_not_detected(self):
        self.assertFalse(auth_guard.is_auth_url(None))
        self.assertFalse(auth_guard.is_auth_url(""))
        self.assertFalse(auth_guard.is_auth_url("   "))

    def test_relative_url_not_detected(self):
        # No scheme/netloc → not a parseable absolute URL.
        self.assertFalse(auth_guard.is_auth_url("/login"))
        self.assertFalse(auth_guard.is_auth_url("login"))


class CheckNavigationTests(unittest.TestCase):
    """check_navigation return shape: ok=True or auth_required error."""

    def test_normal_url_ok_true(self):
        self.assertEqual(
            auth_guard.check_navigation("https://jobs.gupy.io/jobs/123"),
            {"ok": True},
        )

    def test_auth_url_ok_false(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        self.assertFalse(result["ok"])

    def test_auth_url_error_code(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        self.assertEqual(result["error"]["code"], "auth_required")

    def test_auth_url_error_carries_url(self):
        url = "https://portal.example.com/login?next=/jobs"
        result = auth_guard.check_navigation(url)
        self.assertEqual(result["error"]["url"], url)

    def test_detail_is_ptbr(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        detail = result["error"]["detail"]
        self.assertIsInstance(detail, str)
        self.assertTrue(
            any(c in detail for c in "áéíóúãçê"), "detail is not PT-BR accented text"
        )
        self.assertNotIn("error", detail.lower())

    def test_detail_asks_for_manual_login(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        self.assertFalse(result["ok"])
        self.assertIn("login manualmente", result["error"]["detail"])

    def test_detail_never_suggests_credentials(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        detail = result["error"]["detail"].lower()
        self.assertNotIn("preencha", detail)
        self.assertNotIn("digite", detail)
        self.assertNotIn("email", detail)
        self.assertNotIn("senha", detail)
        self.assertNotIn("password", detail)
        self.assertNotIn("usuário", detail)

    def test_job_id_included_when_provided(self):
        result = auth_guard.check_navigation(
            "https://portal.example.com/login", job_id="abc-123"
        )
        self.assertEqual(result["error"]["job_id"], "abc-123")

    def test_job_id_absent_when_not_provided(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        self.assertNotIn("job_id", result["error"])


class NoCredentialsRuleTests(unittest.TestCase):
    """NEVER-FILL RULE: auth_required payloads never suggest filling credentials."""

    def test_never_fill_constant_exists_and_true(self):
        self.assertTrue(auth_guard.NEVER_FILL_CREDENTIALS)

    def test_never_fill_rule_documented_in_module(self):
        module_doc = auth_guard.__doc__ or ""
        self.assertIn("NEVER-FILL", module_doc.upper())

    def test_auth_required_error_has_no_fill_fields(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        serialized = json.dumps(result, ensure_ascii=False).lower()
        for forbidden in ("password", "senha", "username", "usuario",
                          "fill", "value", "field"):
            self.assertNotIn(forbidden, serialized)

    def test_auth_required_error_keys_are_minimal(self):
        result = auth_guard.check_navigation("https://portal.example.com/login")
        self.assertEqual(
            set(result["error"].keys()), {"code", "detail", "url"}
        )

    def test_ok_payload_contains_no_auth_error(self):
        result = auth_guard.check_navigation("https://jobs.gupy.io/jobs/10")
        self.assertNotIn("error", result)


if __name__ == "__main__":
    unittest.main()