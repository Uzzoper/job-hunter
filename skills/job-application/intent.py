#!/usr/bin/env python3
"""
intent.py — planner intent builder (docs/specs/mcp-apply-loop.md, cutover pkg 1).

Extracted from apply.py (--emit-intent): builds the selector-free INTENT JSON
and validates it against the spec contract. Pure stdlib — no browser, no
network, no filesystem writes.

Intent contract (spec l.61-111):
  * The intent is a single object with identity (`intent_id`, `job_id`,
    `job_url`, `portal`), payload (`profile`), and policy gates (`policy`),
    plus optional `metadata` for API-originated jobs. It MUST NOT contain CSS
    selectors, XPaths, element ids, or per-field locators — the executor
    derives every action from the live AX snapshot via classify.py.
  * `--dry-run` is an execution hint OUTSIDE the intent (`"dry_run": true`) —
    never inside the intent object, never part of identity/policy, never
    written to applications/ or the backend.
  * Policy hard gates `never_fill_credentials` / `stop_on_auth_url` cannot be
    disabled from the CLI; only `require_confirmation_before_final_submit`
    flips via --confirmed / --auto-apply.

Portal allow-list: `is_supported_portal()` decides validity WITHOUT the
portals/*.yaml selector mappings (retired in cutover pkg 3; spec l.291-299).
LinkedIn is deliberately unsupported.
"""

from typing import Any, Dict, List, NotRequired, Optional, TypedDict

# Executor loop step budget when --max-steps is not given (spec: default 25).
DEFAULT_MAX_STEPS = 25

# YAML-free portal allow-list. Case-sensitive lowercase on purpose: the legacy
# YAML helper lookup falls back to portals/<name>.yaml which is also
# case-sensitive, so valid/invalid behavior stays identical until the files
# retire.
SUPPORTED_PORTALS = ("gupy", "infojobs")

# Contract: the intent object must never carry these keys (selectors/locators/
# steps never live in the planner output — spec l.63).
_FORBIDDEN_KEYS = frozenset(
    {"selector", "selectors", "xpath", "locator", "fill_form", "steps",
     "dry_run"}
)


def is_supported_portal(portal: Optional[str]) -> bool:
    """True when ``portal`` is on the allow-list (gupy/infojobs).

    YAML-free by design (cutover pkg 1): portal validity no longer depends on
    the presence of ``portals/<portal>.yaml`` / ``helpers/<portal>.py``.
    ``linkedin`` and any unknown/case-variant (``Gupy``, ``"GUPY"``) are
    rejected, mirroring the legacy filename lookup for identical behavior.
    """
    return portal in SUPPORTED_PORTALS


def build_intent(*, intent_id: str, job_id: str, job_url: str, portal: str,
                 profile: Dict[str, Any],
                 require_confirmation: bool,
                 max_steps: int = DEFAULT_MAX_STEPS,
                 never_fill_credentials: bool = True,
                 stop_on_auth_url: bool = True,
                 job_title: Optional[str] = None,
                 job_company: Optional[str] = None,
                 backend_job_id: Optional[Any] = None,
                 api_base_url: Optional[str] = None) -> Dict[str, Any]:
    """Build the selector-free executor INTENT object (mcp-apply-loop spec).

    NO CSS selectors / XPaths / locators and no steps: the executor derives
    every action on the fly from each live AX snapshot via classify.py
    (observe -> classify -> act -> verify).

      * ``require_confirmation`` — False via --confirmed / --auto-apply (same
        gates enforced by the legacy plan path).
      * ``never_fill_credentials`` / ``stop_on_auth_url`` — hard gates that
        cannot be disabled from the CLI; safe-by-default always.
      * ``profile.resume_path`` — executor-side key; legacy portals store the
        CV under ``cv_path``, so map it here (a planner-side ``resume_path`` is
        preferred when present).
      * ``dry_run`` — deliberately NOT a parameter: it is an execution hint
        placed OUTSIDE this object by the caller (spec l.111).

    Returns the intent dict; validation is available via ``validate_intent``.
    """
    intent: Dict[str, Any] = {
        "intent_id": intent_id,
        "job_id": job_id,
        "job_url": job_url,
        "portal": portal,
        "profile": {
            "name": profile.get("name"),
            "email": profile.get("email"),
            "phone": profile.get("phone"),
            "resume_path": profile.get("resume_path") or profile.get("cv_path"),
            "cover_text": profile.get("cover_text"),
        },
        "policy": {
            "require_confirmation_before_final_submit": bool(require_confirmation),
            "never_fill_credentials": bool(never_fill_credentials),
            "stop_on_auth_url": bool(stop_on_auth_url),
            "max_steps": int(max_steps),
        },
    }
    metadata: Dict[str, Any] = {}
    if job_title is not None:
        metadata["job_title"] = job_title
    if job_company is not None:
        metadata["job_company"] = job_company
    if backend_job_id is not None:
        metadata["backend_job_id"] = backend_job_id
    if api_base_url:
        metadata["api_base_url"] = api_base_url
    if metadata:
        intent["metadata"] = metadata
    return intent


# ---------------------------------------------------------------------------
# Intent validation (spec intent contract)
# ---------------------------------------------------------------------------

def _walk_forbidden(obj: Any, path: str = "") -> List[str]:
    """Collect every forbidden key path (selector/xpath/steps/dry_run/...)."""
    found: List[str] = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            current = f"{path}.{key}" if path else str(key)
            if key in _FORBIDDEN_KEYS:
                found.append(f'"{key}"')
                continue  # do not descend into known-bad subtrees
            found.extend(_walk_forbidden(value, current))
    elif isinstance(obj, list):
        for idx, value in enumerate(obj):
            found.extend(_walk_forbidden(value, f"{path}[{idx}]"))
    return found


def validate_intent(intent: Any) -> List[str]:
    """Check ``intent`` against the spec contract; return a list of problems.

    Empty list means the intent is valid. Checks identity fields, the profile,
    the policy gates (required booleans, ``max_steps >= 1``, hard gates cannot
    be disabled), the portal allow-list, and the selector-free rule (no
    selector/xpath/locator/steps/fill_form/dry_run keys anywhere, nested
    included).
    """
    problems: List[str] = []
    if not isinstance(intent, dict):
        return ["intent must be an object"]

    for key in ("intent_id", "job_id", "job_url", "portal", "profile", "policy"):
        if key not in intent:
            problems.append(f"missing required field: {key}")

    if "intent_id" in intent and not isinstance(intent["intent_id"], str):
        problems.append("intent_id must be a string (uuid4)")
    if "job_url" in intent and not isinstance(intent["job_url"], str):
        problems.append("job_url must be a string")
    if "portal" in intent and not is_supported_portal(intent["portal"]):
        problems.append(f"unsupported portal: {intent['portal']}")

    profile = intent.get("profile")
    if not isinstance(profile, dict):
        problems.append("profile must be an object")
    else:
        for key in ("name", "email"):
            if key not in profile:
                problems.append(f"profile missing required field: {key}")

    policy = intent.get("policy")
    if not isinstance(policy, dict):
        problems.append("policy must be an object")
    else:
        for key in ("require_confirmation_before_final_submit",
                    "never_fill_credentials", "stop_on_auth_url"):
            if key not in policy:
                problems.append(f"policy missing required gate: {key}")
            elif not isinstance(policy[key], bool):
                problems.append(f"policy.{key} must be a boolean")
        if "max_steps" not in policy:
            problems.append("policy missing required gate: max_steps")
        else:
            max_steps = policy["max_steps"]
            if isinstance(max_steps, bool) or not isinstance(max_steps, int):
                problems.append("policy.max_steps must be an integer")
            elif max_steps < 1:
                problems.append("policy.max_steps must be >= 1")
        if policy.get("never_fill_credentials") is False:
            problems.append("policy.never_fill_credentials is a hard gate and cannot be False")
        if policy.get("stop_on_auth_url") is False:
            problems.append("policy.stop_on_auth_url is a hard gate and cannot be False")

    for forbidden in _walk_forbidden(intent):
        if forbidden not in problems:
            problems.append(f"intent must not contain selector fields: {forbidden}")

    return problems


# ---------------------------------------------------------------------------
# Machine-readable intent CONTRACT validation (issue #72 — stdlib TypedDicts)
# ---------------------------------------------------------------------------
#
# The prose ``validate_intent`` above stays as the human-readable checker. This
# partner validator lets the bot REFUSE a malformed intent contract with stable,
# machine-readable codes (one per offending field/rule) instead of parsing
# prose sentences. The TypedDicts document the canonical contract shape that
# ``build_intent`` produces; they are NOT runtime-enforced classes — the checks
# are explicit isinstance/type tests so the validator also catches JSON
# round-trip corruption (e.g. an int arriving as a string from a manual edit).

class ProfileContract(TypedDict):
    name: str
    email: str
    phone: NotRequired[Optional[str]]
    resume_path: NotRequired[Optional[str]]
    cover_text: NotRequired[Optional[str]]


class PolicyContract(TypedDict):
    require_confirmation_before_final_submit: bool
    never_fill_credentials: bool
    stop_on_auth_url: bool
    max_steps: int


class MetadataContract(TypedDict):
    job_title: NotRequired[str]
    job_company: NotRequired[str]
    backend_job_id: NotRequired[int]
    api_base_url: NotRequired[str]


class IntentContract(TypedDict):
    intent_id: str
    job_id: str
    job_url: str
    portal: str
    profile: ProfileContract
    policy: PolicyContract
    metadata: NotRequired[MetadataContract]


def _require_str(obj: Dict[str, Any], namespace: str, field: str,
                 problems: List[str]) -> None:
    """Append ``<path>.missing`` / ``<path>.not_a_string`` when a required
    string field is absent or of the wrong type (``namespace`` may be empty)."""
    path = field if not namespace else f"{namespace}.{field}"
    if field not in obj:
        problems.append(f"{path}.missing")
    elif not isinstance(obj[field], str):
        problems.append(f"{path}.not_a_string")


def _forbidden_codes(obj: Any) -> List[str]:
    """Stable codes (``forbidden_key.<name>``) for every forbidden key found
    in the intent, nested included — deduplicated at the end."""
    codes: List[str] = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in _FORBIDDEN_KEYS:
                codes.append(f"forbidden_key.{key}")
                continue  # never descend into known-bad subtrees
            codes.extend(_forbidden_codes(value))
    elif isinstance(obj, list):
        for value in obj:
            codes.extend(_forbidden_codes(value))
    return codes


def validate_intent_contract(intent_obj: Any) -> Dict[str, Any]:
    """Validate an object against the ``IntentContract`` (issue #72).

    Returns ``{"ok": True}`` on conformance, or a machine-readable refusal
    (the shape tests' contract):

        {"ok": False, "error": "invalid_intent_contract",
         "problems": [<stable codes, e.g. "portal.unsupported",
                      "policy.max_steps.not_an_int",
                      "forbidden_key.steps", ...>],
         "detail": <human-readable join of the codes>}

    The required fields mirror the prose ``validate_intent`` (identity +
    profile.name/email + every policy gate); optional profile/metadata keys are
    checked for TYPE when present. Portal allow-list and the selector-free
    rule (``_FORBIDDEN_KEYS``) are enforced with stable codes here too.
    """
    problems: List[str] = []
    if not isinstance(intent_obj, dict):
        return {"ok": False, "error": "invalid_intent_contract",
                "problems": ["intent.not_an_object"],
                "detail": "intent contract must be a JSON object"}

    for key in ("intent_id", "job_id", "job_url", "portal"):
        _require_str(intent_obj, "", key, problems)

    if "portal" in intent_obj and isinstance(intent_obj["portal"], str) \
            and not is_supported_portal(intent_obj["portal"]):
        problems.append("portal.unsupported")

    profile = intent_obj.get("profile")
    if not isinstance(profile, dict):
        problems.append("profile.not_an_object" if "profile" in intent_obj
                        else "profile.missing")
    else:
        for key in ("name", "email"):
            _require_str(profile, "profile", key, problems)
        # Optional profile payload keys — only the TYPE is enforced when present.
        for key in ("phone", "resume_path", "cover_text"):
            value = profile.get(key)
            if value is not None and not isinstance(value, str):
                problems.append(f"profile.{key}.not_a_string")

    policy = intent_obj.get("policy")
    if not isinstance(policy, dict):
        problems.append("policy.not_an_object" if "policy" in intent_obj
                        else "policy.missing")
    else:
        for key in ("require_confirmation_before_final_submit",
                    "never_fill_credentials", "stop_on_auth_url"):
            if key not in policy:
                problems.append(f"policy.{key}.missing")
            elif not isinstance(policy[key], bool):
                problems.append(f"policy.{key}.not_a_bool")
        if "max_steps" not in policy:
            problems.append("policy.max_steps.missing")
        else:
            max_steps = policy["max_steps"]
            if isinstance(max_steps, bool) or not isinstance(max_steps, int):
                problems.append("policy.max_steps.not_an_int")
            elif max_steps < 1:
                problems.append("policy.max_steps.too_small")
        if policy.get("never_fill_credentials") is False:
            problems.append("policy.never_fill_credentials.disabled")
        if policy.get("stop_on_auth_url") is False:
            problems.append("policy.stop_on_auth_url.disabled")

    metadata = intent_obj.get("metadata")
    if metadata is not None and not isinstance(metadata, dict):
        problems.append("metadata.not_an_object")
    elif isinstance(metadata, dict):
        for key, tp in (("job_title", str), ("job_company", str),
                        ("api_base_url", str)):
            if key in metadata and not isinstance(metadata[key], tp):
                problems.append(f"metadata.{key}.not_a_string")
        if "backend_job_id" in metadata and \
                not isinstance(metadata["backend_job_id"], int):
            problems.append("metadata.backend_job_id.not_an_int")

    for code in _forbidden_codes(intent_obj):
        if code not in problems:
            problems.append(code)

    if problems:
        return {"ok": False, "error": "invalid_intent_contract",
                "problems": problems, "detail": "; ".join(problems)}
    return {"ok": True}


if __name__ == "__main__":
    import sys
    sys.exit("intent.py is a library module — run intent_test.py instead.")