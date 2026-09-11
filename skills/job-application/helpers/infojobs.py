# infojobs.py — InfoJobs portal helper (issue #45).
#
# Pure functions returning action dicts that the bot executes through its
# browser tool. No browser/network I/O here — the helpers only build the
# ordered action plan from the portal YAML mapping (portals/infojobs.yaml).
#
# InfoJobs selectors are BEST-EFFORT: the markup may vary between regions and
# over time. Treat them as a starting point and verify before relying on them.
#
# Mirror of helpers/gupy.py — same shared portal interface:
#   click_apply_button / fill_form / handle_cover_letter / submit / apply

from typing import Any, Dict, List

from . import non_submit_fields

DEFAULT_APPLY_BUTTON_SELECTOR = "button[id='apply']"
DEFAULT_SUBMIT_SELECTOR = "button[type='submit']"


def _submit_selector(portal_cfg: Dict[str, Any]) -> str:
    fields = portal_cfg.get("fields") or {}
    submit = fields.get("submit")
    if isinstance(submit, dict) and submit.get("selector"):
        return submit["selector"]
    return DEFAULT_SUBMIT_SELECTOR


def click_apply_button(portal_cfg: Dict[str, Any] = None,
                       url: str = None) -> Dict[str, Any]:
    """Return a click action that opens the portal's apply button."""
    cfg = portal_cfg or {}
    selector = cfg.get("apply_button_selector") or DEFAULT_APPLY_BUTTON_SELECTOR
    return {
        "type": "click",
        "action": "click_apply_button",
        "selector": selector,
        "url": url,
    }


def fill_form(profile: Dict[str, Any],
              portal_cfg: Dict[str, Any] = None) -> Dict[str, Any]:
    """Return a fill_form batch step with all non-submit fields in portal order."""
    cfg = portal_cfg or {}
    fields: List[Dict[str, Any]] = []
    for field in non_submit_fields(cfg):
        source = field.get("source")
        fields.append({
            "name": field["name"],
            "selector": field["selector"],
            "type": field["type"],
            "value": profile.get(source) if source else None,
        })
    return {"type": "fill_form", "action": "fill_form", "fields": fields}


def handle_cover_letter(profile: Dict[str, Any],
                        portal_cfg: Dict[str, Any] = None) -> Dict[str, Any]:
    """Return a fill action for the cover-letter field."""
    cfg = portal_cfg or {}
    fields = cfg.get("fields") or {}
    cover = fields.get("cover_letter")
    selector = cover.get("selector", "") if isinstance(cover, dict) else ""
    return {
        "type": "fill",
        "action": "handle_cover_letter",
        "field": "cover_letter",
        "selector": selector,
        "value": profile.get("cover_text"),
    }


def submit(portal_cfg: Dict[str, Any] = None,
           confirmed: bool = False) -> Dict[str, Any]:
    """Return a submit action. NEVER called without confirmed=True."""
    if not confirmed:
        raise ValueError("submit() requires confirmed=True (explicit confirmation)")
    cfg = portal_cfg or {}
    return {
        "type": "submit",
        "action": "submit",
        "selector": _submit_selector(cfg),
    }


def apply(job_url: str, profile: Dict[str, Any],
          portal_cfg: Dict[str, Any] = None,
          confirmed: bool = False) -> List[Dict[str, Any]]:
    """Orchestrate the InfoJobs portal flow: click -> fill -> cover -> submit."""
    cfg = portal_cfg or {}
    return [
        click_apply_button(cfg, url=job_url),
        fill_form(profile, cfg),
        handle_cover_letter(profile, cfg),
        submit(cfg, confirmed=confirmed),
    ]
