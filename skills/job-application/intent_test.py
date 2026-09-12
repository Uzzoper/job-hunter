#!/usr/bin/env python3
"""
intent_test.py — mcp-apply-loop (docs/specs/mcp-apply-loop.md), cutover pkg 1
tests for intent.py: the selector-free planner-intent builder extracted from
apply.py, the intent contract validation, and the YAML-free portal allow-list.

Spec anchors:
  * intent contract (l.61-111): NO CSS selectors/XPaths/element ids/locators;
    identity + profile + policy (+ optional metadata).
  * l.111: `--dry-run` is an execution hint OUTSIDE the intent — never inside
    the intent object, never part of identity/policy.
  * delete/retire table (l.291-299): portals/*.yaml retire in a later package,
    so portal validity is decided here by allow-list, not by file presence.

Plain unittest — no browser, no MCP, no filesystem writes.

Run:
    python3 intent_test.py
"""

import json
import os
import sys
import unittest

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import intent  # noqa: E402  (RED phase: module does not exist yet)

VALID_PROFILE = {
    "name": "Juan Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 11 99999-0000",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga.",
}

JOB_URL = "https://jobs.gupy.io/jobs/8472"


def build(**overrides):
    """Build a valid intent via the module under test, then override."""
    kwargs = {
        "intent_id": "7c2a1f4e-9b6d-4c3a-8f1e-2d5a9b0c71e3",
        "job_id": "dev-backend-jr-8472",
        "job_url": JOB_URL,
        "portal": "gupy",
        "profile": VALID_PROFILE,
        "require_confirmation": True,
        "job_title": "Back-end Developer Jr",
        "job_company": "ACME Tech",
        "backend_job_id": 7,
        "api_base_url": "http://localhost:8080",
    }
    kwargs.update(overrides)
    return intent.build_intent(**kwargs)


def _walk(obj, walk_fn, path=""):
    """Depth-first walk of nested dicts/lists, calling walk_fn(value, path)."""
    if isinstance(obj, dict):
        for key, value in obj.items():
            walk_fn(value, f"{path}.{key}" if path else key)
            _walk(value, walk_fn, f"{path}.{key}" if path else key)
    elif isinstance(obj, list):
        for idx, value in enumerate(obj):
            _walk(value, walk_fn, f"{path}[{idx}]")


class BuildIntentTests(unittest.TestCase):
    """build_intent(...) shapes the selector-free intent object."""

    def test_build_intent_shape(self):
        intent_obj = build()
        self.assertEqual(set(intent_obj.keys()),
                         {"intent_id", "job_id", "job_url", "portal",
                          "profile", "policy", "metadata"})
        self.assertEqual(intent_obj["job_id"], "dev-backend-jr-8472")
        self.assertEqual(intent_obj["job_url"], JOB_URL)
        self.assertEqual(intent_obj["portal"], "gupy")
        self.assertEqual(set(intent_obj["profile"].keys()),
                         {"name", "email", "phone", "resume_path", "cover_text"})
        self.assertEqual(set(intent_obj["policy"].keys()),
                         {"require_confirmation_before_final_submit",
                          "never_fill_credentials", "stop_on_auth_url",
                          "max_steps"})
        self.assertEqual(intent_obj["metadata"]["job_title"], "Back-end Developer Jr")
        self.assertEqual(intent_obj["metadata"]["api_base_url"], "http://localhost:8080")

    def test_resume_path_maps_from_cv_path(self):
        intent_obj = build()
        self.assertEqual(intent_obj["profile"]["resume_path"], VALID_PROFILE["cv_path"])

    def test_resume_path_prefers_explicit_key(self):
        profile = dict(VALID_PROFILE, resume_path="/explicit.pdf")
        intent_obj = build(profile=profile)
        self.assertEqual(intent_obj["profile"]["resume_path"], "/explicit.pdf")

    def test_build_intent_has_no_selector_fields(self):
        text = json.dumps(build()).lower()
        for forbidden in ("selector", "input[", "button[", "xpath", "fill_form",
                          "\"steps\"", "locator"):
            self.assertNotIn(forbidden, text,
                             f"intent must not carry {forbidden!r}")

    def test_build_intent_never_contains_dry_run(self):
        # Spec l.111: --dry-run is an execution hint OUTSIDE the intent.
        for with_flag in (False, True):
            intent_obj = build()  # builder has no dry_run knob at all
            self.assertNotIn("dry_run", intent_obj)

    def test_confirmed_flips_policy(self):
        confirmed = build(require_confirmation=False)
        self.assertFalse(
            confirmed["policy"]["require_confirmation_before_final_submit"])
        plain = build(require_confirmation=True)
        self.assertTrue(plain["policy"]["require_confirmation_before_final_submit"])
        # Hard gates cannot be disabled by the confirmation flag.
        self.assertTrue(plain["policy"]["never_fill_credentials"])
        self.assertTrue(plain["policy"]["stop_on_auth_url"])

    def test_max_steps_default_and_flag(self):
        self.assertEqual(build()["policy"]["max_steps"], intent.DEFAULT_MAX_STEPS)
        self.assertEqual(build(max_steps=10)["policy"]["max_steps"], 10)
        self.assertEqual(intent.DEFAULT_MAX_STEPS, 25)

    def test_metadata_omitted_when_empty(self):
        bare = build(job_title=None, job_company=None, backend_job_id=None,
                     api_base_url=None)
        self.assertNotIn("metadata", bare)


class ValidateIntentTests(unittest.TestCase):
    """validate_intent(...) enforces the intent contract."""

    def test_validate_accepts_valid_intent(self):
        self.assertEqual(intent.validate_intent(build()), [])

    def test_validate_requires_identity_fields(self):
        for missing in ("intent_id", "job_id", "job_url", "portal"):
            broken = build()
            del broken[missing]
            problems = intent.validate_intent(broken)
            self.assertTrue(any(missing in p for p in problems),
                            f"missing {missing} must be flagged: {problems}")

    def test_validate_requires_profile_and_policy(self):
        broken = build()
        del broken["profile"]
        del broken["policy"]
        problems = intent.validate_intent(broken)
        self.assertTrue(any("profile" in p for p in problems))
        self.assertTrue(any("policy" in p for p in problems))

    def test_validate_requires_policy_gates(self):
        for missing in ("require_confirmation_before_final_submit",
                        "never_fill_credentials", "stop_on_auth_url",
                        "max_steps"):
            broken = build()
            del broken["policy"][missing]
            problems = intent.validate_intent(broken)
            self.assertTrue(any(missing in p for p in problems),
                            f"missing {missing} must be flagged: {problems}")

    def test_validate_rejects_zero_max_steps(self):
        broken = build()
        broken["policy"]["max_steps"] = 0
        self.assertTrue(any("max_steps" in p for p in intent.validate_intent(broken)))

    def test_validate_rejects_disabled_hard_gates(self):
        broken = build()
        broken["policy"]["never_fill_credentials"] = False
        self.assertTrue(any("never_fill_credentials" in p
                            for p in intent.validate_intent(broken)))
        broken = build()
        broken["policy"]["stop_on_auth_url"] = False
        self.assertTrue(any("stop_on_auth_url" in p
                            for p in intent.validate_intent(broken)))

    def test_validate_rejects_selector_fields(self):
        broken = build()
        broken["steps"] = [{"type": "fill_form",
                            "fields": [{"name": "nome", "selector": "input[name='name']"}]}]
        problems = intent.validate_intent(broken)
        self.assertTrue(any("selector" in p for p in problems))
        self.assertTrue(any("\"steps\"" in p for p in problems))

    def test_validate_rejects_nested_selector(self):
        broken = build()
        broken["profile"]["xpath"] = "//form[1]/input"
        problems = intent.validate_intent(broken)
        self.assertTrue(any("xpath" in p for p in problems))

    def test_validate_rejects_unsupported_portal(self):
        broken = build(portal="linkedin")
        problems = intent.validate_intent(broken)
        self.assertTrue(any("portal" in p for p in problems))

    def test_validate_rejects_non_dict(self):
        self.assertTrue(intent.validate_intent("not an intent"))

    def test_dry_run_never_validated_as_intent_field(self):
        # Even when fed inside (mis-build), the checker flags it as forbidden.
        broken = build()
        broken["dry_run"] = True
        self.assertTrue(any("dry_run" in p for p in intent.validate_intent(broken)))


class SupportedPortalTests(unittest.TestCase):
    """YAML-free portal allow-list (cutover pkg 1; portals/*.yaml retire later)."""

    def test_accepts_gupy(self):
        self.assertTrue(intent.is_supported_portal("gupy"))

    def test_accepts_infojobs(self):
        self.assertTrue(intent.is_supported_portal("infojobs"))

    def test_rejects_linkedin(self):
        self.assertFalse(intent.is_supported_portal("linkedin"))

    def test_rejects_unknown_and_case_variants(self):
        # Case-sensitive lowercase, mirroring the old YAML filename lookup —
        # valid/invalid behavior stays identical until portals/ retire.
        for bad in ("foo", "Gupy", "GUPY", "", None):
            self.assertFalse(intent.is_supported_portal(bad), repr(bad))


if __name__ == "__main__":
    unittest.main()