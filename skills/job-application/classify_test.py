#!/usr/bin/env python3
"""
classify_test.py — mcp-apply-loop (docs/specs/mcp-apply-loop.md) phase 3 tests
for classify.py (the pure page classifier of the observe->classify->act->verify
executor loop).

Plain unittest, no browser, no MCP: the classifier maps an AX snapshot text +
current URL to one page kind (form|triagem|review|sucesso|auth|erro|start) plus
the matched signals used for loop-stall detection.

Spec classify_test rows:
  1. test_form_vs_triagem_vs_review_vs_sucesso
  2. test_success_text_matching_is_case_insensitive
  3. test_success_url_segment_detected
  4. test_auth_url_never_classified_as_form
  5. test_error_and_validation_detected
Plus start-page, credential-field, fallback and signals-shape coverage.

Run:
    python3 classify_test.py
    python3 -m pytest classify_test.py
"""

import os
import sys
import unittest

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import classify  # noqa: E402  (RED phase: module does not exist yet)


def node(role, name="", value=""):
    """Build an ax_tree-normalized AX node ({role, name, value})."""
    return {"role": role, "name": name, "value": value}


JOB_URL = "https://jobs.gupy.io/jobs/8472"
SUCCESS_URL = "https://jobs.gupy.io/candidaturas/confirmacao/8472"


class ClassifyPageTests(unittest.TestCase):
    """classify_page(url, ax_nodes) -> {"page": ..., "signals": [...]}."""

    def test_form_page_detected(self):
        ax = [
            node("textbox", "Nome"),
            node("textbox", "Email"),
            node("button", "Enviar"),
        ]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "form")
        self.assertIn("textbox", result["signals"])

    def test_triagem_page_detected(self):
        ax = [
            node("radio", "Sim"),
            node("radio", "Não"),
            node("radio group", "Já trabalhou com Java?"),
            node("button", "Próximo"),
        ]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "triagem")
        self.assertIn("radio", result["signals"])

    def test_review_page_detected(self):
        ax = [
            node("heading", "Confira seus dados"),  # summary has no inputs
            node("staticText", "Juan Antonio Peruzzo"),
            node("button", "Enviar candidatura"),
        ]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "review")

    def test_sucesso_text_detected(self):
        ax = [node("heading", "Inscrição realizada com sucesso")]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "sucesso")
        self.assertIn("success_text:Inscrição realizada", result["signals"])

    def test_success_text_matching_is_case_insensitive(self):
        ax = [node("heading", "INSCRIÇÃO realizada")]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "sucesso")

    def test_success_url_segment_detected(self):
        ax = [node("main", "Obrigado pela sua candidatura")]
        result = classify.classify_page(SUCCESS_URL, ax)
        self.assertEqual(result["page"], "sucesso")
        self.assertIn("success_url:confirmacao", result["signals"])

    def test_auth_url_never_classified_as_form(self):
        auth_url = "https://jobs.gupy.io/candidates/login?return=/jobs/8472"
        ax = [node("textbox", "Email"), node("textbox", "Senha")]
        result = classify.classify_page(auth_url, ax)
        self.assertEqual(result["page"], "auth")
        self.assertIn("auth_url", result["signals"])

    def test_credential_fields_never_filled(self):
        # Non-auth URL, but a password field is present — must never be a form.
        ax = [node("textbox", "E-mail"), node("textbox", "Senha")]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "auth")
        self.assertIn("credential_password", result["signals"])

    def test_error_and_validation_detected(self):
        ax = [
            node("textbox", "Nome"),
            node("button", "Enviar"),
            node("alert", "Preencha todos os campos"),
        ]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "erro")
        self.assertIn("error_text:Preencha todos os campos", result["signals"])

    def test_start_page_detected(self):
        ax = [
            node("heading", "Desenvolvedor Back-end Jr"),
            node("button", "Candidatar-se"),
        ]
        result = classify.classify_page(JOB_URL, ax)
        self.assertEqual(result["page"], "start")
        self.assertIn("apply_button:Candidatar-se", result["signals"])

    def test_unrecognized_page_is_safe_erro(self):
        # Nothing recognizable: classify as erro (safe — recovery or budget,
        # never a blind fill, never a record).
        result = classify.classify_page(JOB_URL, [node("main", "Manutenção")])
        self.assertEqual(result["page"], "erro")
        self.assertIn("unrecognized_page", result["signals"])

    def test_signals_is_a_list(self):
        result = classify.classify_page(JOB_URL, [node("textbox", "Nome")])
        self.assertIsInstance(result["signals"], list)

    def test_empty_ax_never_classified_as_form(self):
        result = classify.classify_page(JOB_URL, [])
        self.assertEqual(result["page"], "erro")
        self.assertIn("unrecognized_page", result["signals"])


if __name__ == "__main__":
    unittest.main()