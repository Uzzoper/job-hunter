# helpers/ — domain-specific portal helper modules (issue #45).
#
# Each portal has its own module: helpers/gupy.py, helpers/infojobs.py, etc.
# This package must NOT import portal modules at top level — helpers are loaded
# lazily by name via apply.load_helper(). The only shared code kept here is
# genuinely common (see non_submit_fields); portal-specific logic stays in the
# per-portal modules. Duplication beats premature abstraction.

from typing import Any, Dict, List


def non_submit_fields(portal_cfg: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Return the portal's form fields (non-submit) as [{name, selector, type, source?}].

    Reads ``portal_cfg["fields"]``, a dict of ``{name: {selector, type, source}}``,
    and returns the entries in portal (YAML) order, skipping the ``submit`` field
    (which carries no value source and is only emitted after confirmation).
    """
    fields = portal_cfg.get("fields")
    if not isinstance(fields, dict):
        return []
    result: List[Dict[str, Any]] = []
    for fname, spec in fields.items():
        if not isinstance(spec, dict) or spec.get("type") == "submit":
            continue
        item: Dict[str, Any] = {"name": fname, "selector": spec.get("selector", ""),
                                "type": spec.get("type", "")}
        if spec.get("source"):
            item["source"] = spec["source"]
        result.append(item)
    return result
