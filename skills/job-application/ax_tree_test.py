#!/usr/bin/env python3
"""
ax_tree_test.py — issue #43 tests for ax_tree.py (accessibility-tree verification
as a fast, vision-free replacement for screenshots).

Covers:
  * snapshot_from_cdp_response — flatten a nested CDP Accessibility.getFullAXTree
    result into a normalized node list, skipping ignored nodes.
  * find_in_ax_tree — case-insensitive substring matching over role/name/value;
    structured dict query ({"role": ..., "name": ...}); no-match -> empty; empty tree.
  * fetch_ax_tree — with an injected fake transport (cdp_post), and the
    transport-missing error when no transport is provided.

Plain unittest (pytest-compatible). Stdlib only. No browser/network I/O.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# Allow direct import when running from the skill dir.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ax_tree  # noqa: E402

APPLY_PATH = Path(__file__).resolve().parent / "apply.py"
GUPY_URL = "https://jobs.gupy.io/jobs/12345-desenvolvedor-java"

VALID_PROFILE = {
    "name": "Juan Antonio Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 42 99833-1363",
    "cv_path": "/home/juan/cv.pdf",
    "cover_text": "Olá! Gostaria de me candidatar à vaga de desenvolvedor.",
}


def _ax_node(node_id, role, name=None, value=None, ignored=False,
             backend_dom=None):
    """Build a CDP Accessibility.getFullAXTree node dict (nested role/name/value)."""
    node = {"nodeId": node_id, "ignored": ignored}
    if role is not None:
        node["role"] = {"value": role}
    if name is not None:
        node["name"] = {"value": name}
    if value is not None:
        node["value"] = {"value": value}
    if backend_dom is not None:
        node["backendDOMNodeId"] = backend_dom
    return node


class SnapshotParserTests(unittest.TestCase):
    """snapshot_from_cdp_response flattens the CDP AX payload into nodes."""

    def test_flattens_nested_ax_json(self):
        payload = {"nodes": [
            _ax_node("1", "button", name="Enviar", backend_dom=101),
            _ax_node("2", "textbox", name="nome", value="", backend_dom=102),
        ]}
        nodes = ax_tree.snapshot_from_cdp_response(payload)
        self.assertEqual(len(nodes), 2)
        self.assertEqual(nodes[0]["role"], "button")
        self.assertEqual(nodes[0]["name"], "Enviar")
        self.assertEqual(nodes[0]["backendNodeId"], 101)
        self.assertFalse(nodes[0]["ignored"])
        self.assertEqual(nodes[1]["value"], "")

    def test_skips_ignored_nodes(self):
        payload = {"nodes": [
            _ax_node("1", "button", name="Enviar"),
            _ax_node("2", "generic", ignored=True),
            _ax_node("3", "textbox", name="email"),
        ]}
        nodes = ax_tree.snapshot_from_cdp_response(payload)
        self.assertEqual(len(nodes), 2)
        self.assertEqual([n["role"] for n in nodes], ["button", "textbox"])

    def test_empty_payload_returns_empty_list(self):
        self.assertEqual(ax_tree.snapshot_from_cdp_response({}), [])
        self.assertEqual(ax_tree.snapshot_from_cdp_response({"nodes": []}), [])

    def test_node_missing_role_name_keeps_empty_strings(self):
        nodes = ax_tree.snapshot_from_cdp_response(
            {"nodes": [{"nodeId": "1", "ignored": False}]}
        )
        self.assertEqual(len(nodes), 1)
        self.assertEqual(nodes[0]["role"], "")
        self.assertEqual(nodes[0]["name"], "")
        self.assertEqual(nodes[0]["value"], "")


class FindInAxTreeTests(unittest.TestCase):
    """find_in_ax_tree matches case-insensitively over role/name/value."""

    def setUp(self):
        self.nodes = ax_tree.snapshot_from_cdp_response({"nodes": [
            _ax_node("1", "button", name="Enviar candidatura"),
            _ax_node("2", "button", name="Salvar rascunho"),
            _ax_node("3", "textbox", name="nome completo"),
            _ax_node("4", "textbox", name="email", value="JUAN@example.com"),
        ]})

    def test_matches_by_name_substring(self):
        result = ax_tree.find_in_ax_tree(self.nodes, "enviar")
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["role"], "button")
        self.assertEqual(result[0]["name"], "Enviar candidatura")

    def test_matches_case_insensitive(self):
        result = ax_tree.find_in_ax_tree(self.nodes, "ENVIAR")
        self.assertEqual(len(result), 1)
        # "juan" matches only the textbox value "JUAN@example.com" (case-insensitive).
        result = ax_tree.find_in_ax_tree(self.nodes, "juan")
        self.assertEqual(len(result), 1)
        result = ax_tree.find_in_ax_tree(self.nodes, "NOME COMPLETO")
        self.assertEqual(len(result), 1)

    def test_structured_role_and_name_query(self):
        result = ax_tree.find_in_ax_tree(
            self.nodes, {"role": "button", "name": "enviar"}
        )
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["role"], "button")

    def test_structured_role_only_query(self):
        result = ax_tree.find_in_ax_tree(self.nodes, {"role": "textbox"})
        self.assertEqual(len(result), 2)

    def test_no_match_returns_empty(self):
        self.assertEqual(ax_tree.find_in_ax_tree(self.nodes, "inexistente"), [])

    def test_empty_tree_returns_empty(self):
        self.assertEqual(ax_tree.find_in_ax_tree([], "enviar"), [])


class FetchAxTreeTests(unittest.TestCase):
    """fetch_ax_tree delegates to an injected transport or errors cleanly."""

    def test_without_transport_returns_transport_required(self):
        result = ax_tree.fetch_ax_tree("http://localhost:9222")
        self.assertEqual(result["error"], "cdp_transport_required")

    def test_with_fake_transport_returns_parsed_nodes(self):
        payload = {"nodes": [
            _ax_node("1", "button", name="Enviar"),
        ]}

        def fake_cdp_post(method, params):
            self.assertEqual(method, "Accessibility.getFullAXTree")
            return payload

        result = ax_tree.fetch_ax_tree(
            "http://localhost:9222", cdp_post=fake_cdp_post
        )
        self.assertEqual(result["nodes"][0]["name"], "Enviar")

    def test_transport_raises_returns_error(self):
        def failing_cdp_post(method, params):
            raise RuntimeError("transport down")

        result = ax_tree.fetch_ax_tree(
            "http://localhost:9222", cdp_post=failing_cdp_post
        )
        self.assertEqual(result["error"], "ax_fetch_failed")
        self.assertIn("transport down", result["detail"])


if __name__ == "__main__":
    unittest.main()
