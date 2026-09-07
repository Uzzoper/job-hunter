#!/usr/bin/env python3
"""
ax_tree.py — accessibility-tree verification for the job-application skill (issue #43).

Replaces screenshot-based verification with a fast, vision-free check against
the page's accessibility tree. The bot fetches the AX tree through its real CDP
session (a WebSocket transport it owns) and inspects it for e.g. a submit button
with a known accessible name instead of taking a screenshot and paying an LLM
vision cost.

Why NOT in navigation.py? navigation.py is stateless by design (pure URL logic,
never touches a browser or the network). AX fetching needs a CDP transport, so
it lives in its own module.

Stdlib only: urllib, json, typing. No pip dependencies.

The AX transport is pluggable: the bot (or a test) injects a ``cdp_post``
callable that sends a CDP command and returns the response payload. fetch_ax_tree
with no transport returns a structured error — this module never assumes a real
WebSocket session exists here.

Usage:
    from ax_tree import fetch_ax_tree, find_in_ax_tree
    result = fetch_ax_tree(cdp_url, cdp_post=bot_cdp_post)
    matches = find_in_ax_tree(result.get("nodes", []), {"role": "button", "name": "Enviar"})
"""

import json
from typing import Any, Dict, List, Optional

# ---------------------------------------------------------------------------
# CDP payload parsing
# ---------------------------------------------------------------------------

def _extract_value(struct) -> str:
    """Extract the ``value`` of a CDP AX property struct (e.g. {"value": "x"})."""
    if isinstance(struct, dict):
        value = struct.get("value")
        if value is None:
            return ""
        return str(value)
    return str(struct) if struct is not None else ""


def snapshot_from_cdp_response(payload: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Flatten a CDP Accessibility.getFullAXTree payload into a normalized node list.

    The CDP payload looks like ``{"nodes": [{"nodeId", "role": {"value": ...},
    "name": {"value": ...}, "value": {"value": ...}, "backendDOMNodeId",
    "ignored": bool}]}``. This returns a flat list of ``{role, name, value,
    backendNodeId, ignored}`` dicts, skipping nodes that are ``ignored`` (hidden
    from assistive tech — not part of the rendered accessibility surface).

    Returns ``[]`` on an empty/malformed payload.
    """
    nodes = (payload or {}).get("nodes")
    if not isinstance(nodes, list):
        return []
    result: List[Dict[str, Any]] = []
    for node in nodes:
        if not isinstance(node, dict) or node.get("ignored"):
            continue
        result.append({
            "role": _extract_value(node.get("role")),
            "name": _extract_value(node.get("name")),
            "value": _extract_value(node.get("value")),
            "backendNodeId": node.get("backendDOMNodeId"),
            "ignored": False,
        })
    return result


# ---------------------------------------------------------------------------
# Pure search over the normalized node list
# ---------------------------------------------------------------------------

def _node_text(node: Dict[str, Any]) -> str:
    """Concatenated role+name+value of a node for substring search."""
    return " ".join(str(node.get(k) or "") for k in ("role", "name", "value"))


def _matches(node: Dict[str, Any], query) -> bool:
    """Match a single node against a string or structured dict query."""
    if isinstance(query, str):
        return query.lower() in _node_text(node).lower()
    if isinstance(query, dict):
        for key, expected in query.items():
            actual = node.get(key)
            if actual is None or str(expected).lower() not in str(actual).lower():
                return False
        return True
    return False


def find_in_ax_tree(nodes: List[Dict[str, Any]], query) -> List[Dict[str, Any]]:
    """Return the nodes matching *query*.

    ``query`` may be:
      * a plain string — case-insensitive substring over ``role name value``; or
      * a dict like ``{"role": "button", "name": "Enviar"}`` — every provided
        key must be a case-insensitive substring of the node's corresponding
        field.

    Returns the matching nodes (with role/name context) so the bot can confirm
    e.g. a submit button with a known accessible name is present.
    """
    if not nodes:
        return []
    return [node for node in nodes if _matches(node, query)]


# ---------------------------------------------------------------------------
# Fetch via an injected CDP transport
# ---------------------------------------------------------------------------

def fetch_ax_tree(cdp_url: str,
                  timeout: int = 5,
                  cdp_post=None) -> Optional[Dict[str, Any]]:
    """Fetch and normalize the accessibility tree via an injected CDP transport.

    ``cdp_post`` is a callable ``cdp_post(method, params) -> payload`` that
    sends a CDP command over the bot's real transport (typically WebSocket) and
    returns the command's result payload. When no transport is provided this
    returns ``{"error": "cdp_transport_required"}`` — the real CDP access is
    owned by the bot, not by this stdlib module.

    On success returns ``{"nodes": <normalized node list>}``. ``cdp_url`` and
    ``timeout`` are accepted for interface stability and future use; the actual
    transport is injected.
    """
    if cdp_post is None:
        return {"error": "cdp_transport_required"}
    try:
        payload = cdp_post("Accessibility.getFullAXTree", {})
    except Exception as exc:  # transport failure / timeout
        return {"error": "ax_fetch_failed", "detail": str(exc)}
    return {"nodes": snapshot_from_cdp_response(payload)}
