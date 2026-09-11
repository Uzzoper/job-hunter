#!/usr/bin/env python3
"""
helpers_test.py — issue #45 tests for the domain-specific portal helpers.

Covers:
  * gupy flow order (click_apply_button -> fill_form -> handle_cover_letter -> submit)
  * submit()/apply() require confirmed=True
  * fill_form batch shape (portal-ordered fields: name, email, phone, cv_upload, cover_letter)
  * infojobs same interface + signature parity with gupy
  * unknown portal via apply.load_portal/load_helper and the CLI
  * lazy imports (importing the helpers package must NOT import portal modules)
  * LinkedIn explicitly out of scope (no linkedin helper/yaml resolvable)

Plain unittest (pytest-compatible). Stdlib only. No browser/network I/O.
"""

import importlib
import inspect
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

# Allow direct import when running from the skill dir.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apply  # noqa: E402
import helpers  # noqa: E402

PORTALS_DIR = Path(__file__).resolve().parent / "portals"
HELPERS_DIR = Path(__file__).resolve().parent / "helpers"
APPLY_PATH = Path(__file__).resolve().parent / "apply.py"

GUPY_URL = "https://jobs.gupy.io/jobs/12345-desenvolvedor-java"
INFOJOBS_URL = "https://www.infojobs.com.br/vaga/98765-desenvolvedor-java.aspx"

VALID_PROFILE = {
    "name": "Juan Antonio Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 42 99833-1363",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga de desenvolvedor.",
}

GUPY_CFG = apply.load_portal("gupy")
INFOJOBS_CFG = apply.load_portal("infojobs")


class GupyFlowTests(unittest.TestCase):
    """gupy.apply orchestrates the portal flow in the correct order."""

    @classmethod
    def setUpClass(cls):
        cls.gupy = importlib.import_module("helpers.gupy")

    def test_apply_emits_flow_in_order(self):
        plan = self.gupy.apply(GUPY_URL, VALID_PROFILE, portal_cfg=GUPY_CFG, confirmed=True)
        self.assertEqual(
            [a["action"] for a in plan],
            ["click_apply_button", "fill_form", "handle_cover_letter", "submit"],
        )

    def test_apply_requires_confirmed(self):
        with self.assertRaises(ValueError):
            self.gupy.apply(GUPY_URL, VALID_PROFILE, portal_cfg=GUPY_CFG)

    def test_submit_requires_confirmed(self):
        with self.assertRaises(ValueError):
            self.gupy.submit(portal_cfg=GUPY_CFG)
        result = self.gupy.submit(portal_cfg=GUPY_CFG, confirmed=True)
        self.assertEqual(result["action"], "submit")
        self.assertEqual(result["type"], "submit")

    def test_click_apply_button_shape(self):
        result = self.gupy.click_apply_button(portal_cfg=GUPY_CFG, url=GUPY_URL)
        self.assertEqual(result["type"], "click")
        self.assertEqual(result["action"], "click_apply_button")
        self.assertEqual(result["selector"], "button[id='apply-button']")
        self.assertEqual(result["url"], GUPY_URL)

    def test_fill_form_batch_shape(self):
        result = self.gupy.fill_form(VALID_PROFILE, portal_cfg=GUPY_CFG)
        self.assertEqual(result["type"], "fill_form")
        self.assertEqual(result["action"], "fill_form")
        names = [f["name"] for f in result["fields"]]
        self.assertEqual(names, ["name", "email", "phone", "cv_upload", "cover_letter"])
        by_name = {f["name"]: f for f in result["fields"]}
        self.assertEqual(by_name["name"]["value"], VALID_PROFILE["name"])
        self.assertEqual(by_name["name"]["selector"], "input[name='name']")
        self.assertEqual(by_name["name"]["type"], "fill")
        self.assertEqual(by_name["cv_upload"]["type"], "upload")
        self.assertEqual(by_name["cv_upload"]["value"], VALID_PROFILE["cv_path"])

    def test_handle_cover_letter_shape(self):
        result = self.gupy.handle_cover_letter(VALID_PROFILE, portal_cfg=GUPY_CFG)
        self.assertEqual(result["type"], "fill")
        self.assertEqual(result["action"], "handle_cover_letter")
        self.assertEqual(result["field"], "cover_letter")
        self.assertEqual(result["value"], VALID_PROFILE["cover_text"])


class InfoJobsInterfaceTests(unittest.TestCase):
    """infjobs exposes the same interface + signature parity as gupy."""

    @classmethod
    def setUpClass(cls):
        cls.infojobs = importlib.import_module("helpers.infojobs")
        cls.gupy = importlib.import_module("helpers.gupy")

    def test_loads_portal(self):
        self.assertIsNotNone(INFOJOBS_CFG)
        self.assertEqual(INFOJOBS_CFG["portal"], "infojobs")

    def test_apply_emits_flow_in_order(self):
        plan = self.infojobs.apply(INFOJOBS_URL, VALID_PROFILE, portal_cfg=INFOJOBS_CFG, confirmed=True)
        self.assertEqual(
            [a["action"] for a in plan],
            ["click_apply_button", "fill_form", "handle_cover_letter", "submit"],
        )

    def test_click_apply_button_selector(self):
        result = self.infojobs.click_apply_button(portal_cfg=INFOJOBS_CFG, url=INFOJOBS_URL)
        self.assertEqual(result["selector"], "button[id='apply']")

    def test_submit_requires_confirmed(self):
        with self.assertRaises(ValueError):
            self.infojobs.submit(portal_cfg=INFOJOBS_CFG)

    def test_signature_parity_with_gupy(self):
        for fn in ("click_apply_button", "fill_form", "handle_cover_letter",
                   "submit", "apply"):
            g = inspect.signature(getattr(self.gupy, fn))
            i = inspect.signature(getattr(self.infojobs, fn))
            self.assertEqual(list(g.parameters), list(i.parameters),
                             f"signature mismatch for {fn}")


class UnknownPortalTests(unittest.TestCase):
    """Unknown portals error cleanly via API and CLI."""

    def test_load_helper_returns_none_for_unknown(self):
        self.assertIsNone(apply.load_helper("nope"))
        self.assertIsNone(apply.load_helper("linkedin"))

    def test_load_portal_returns_none_for_unknown(self):
        self.assertIsNone(apply.load_portal("linkedin"))

    def test_no_linkedin_files_resolvable(self):
        self.assertFalse((PORTALS_DIR / "linkedin.yaml").exists())
        self.assertFalse((HELPERS_DIR / "linkedin.py").exists())

    def test_cli_unknown_portal_errors_cleanly(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile.json"
            profile.write_text(json.dumps(VALID_PROFILE), encoding="utf-8")
            proc = subprocess.run(
                [sys.executable, str(APPLY_PATH),
                 "--job-url", GUPY_URL, "--profile", str(profile),
                 "--portal", "linkedin", "--memory-dir", tmp, "--dry-run"],
                capture_output=True, text=True,
            )
            self.assertEqual(proc.returncode, 1)
            data = json.loads(proc.stdout)
            self.assertEqual(data["error"], "unknown_portal")
            self.assertIn("linkedin", data["detail"])


class LazyImportTests(unittest.TestCase):
    """Importing the helpers package must NOT import portal modules."""

    def test_helpers_package_does_not_import_portals(self):
        code = (
            "import sys; import helpers; "
            "mods = set(sys.modules.keys()); "
            "print('helpers.gupy' in mods, 'helpers.infojobs' in mods)"
        )
        env = dict(os.environ)
        env["PYTHONPATH"] = str(Path(__file__).resolve().parent)
        proc = subprocess.run(
            [sys.executable, "-c", code], capture_output=True, text=True, env=env
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(proc.stdout.strip().split(), ["False", "False"])


if __name__ == "__main__":
    unittest.main()
