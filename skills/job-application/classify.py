#!/usr/bin/env python3
"""
classify.py — pure page classifier for the observe->classify->act->verify
executor apply loop (docs/specs/mcp-apply-loop.md, phase 3).

The executor (Hermes bot driving the Playwright MCP server) observes the
accessibility tree (AX snapshot) + current URL and hands both to
classify_page(): a pure function that maps the page to one of the state-machine
page kinds — form | triagem | review | sucesso | auth | erro | start — plus the
matched signals (used by the loop for stall detection and recovery hints).

Design: stdlib-only and stateless. This module never touches a browser, the
filesystem, or the network. It reuses:
  * navigation.is_auth_url            — the auth-page stop gate (unchanged)
  * verdict.SUCCESS_PHRASES /
    SUCCESS_URL_SEGMENTS              — single source of truth for success evidence

Classification priority (hard stops first, then page kinds by control shape):
  auth -> sucesso -> erro -> review -> start -> triagem -> form
An unrecognized page is classified as erro (safe fallback: the executor retries
within its step budget and ends INCOMPLETE — never a blind fill, never a record).
"""

from typing import Any, Dict, List, Optional, Tuple

from navigation import is_auth_url
from verdict import SUCCESS_PHRASES, SUCCESS_URL_SEGMENTS

# ---------------------------------------------------------------------------
# Page kinds (executor state machine)
# ---------------------------------------------------------------------------

FORM = "form"
TRIAGEM = "triagem"
REVIEW = "review"
SUCESSO = "sucesso"
AUTH = "auth"
ERRO = "erro"
START = "start"

PAGE_KINDS = (FORM, TRIAGEM, REVIEW, SUCESSO, AUTH, ERRO, START)

# ---------------------------------------------------------------------------
# Signal vocabulary
# ---------------------------------------------------------------------------

# Editable controls that make a page a candidate form.
_EDITABLE_ROLES = frozenset(
    {"textbox", "textarea", "combobox", "searchbox", "fileinput", "file"}
)

# Question-group controls that make a page a candidate triagem (screening).
_QUESTION_ROLES = frozenset({"radio", "radio group", "checkbox", "switch"})

# Entry button names -> start (job detail page, "Candidatar-se").
# Natural casing: these constants double as signal labels for the executor.
_START_APPLY_NAMES = (
    "Candidatar-se", "Candidatar agora", "Candidatar me", "Candidatar-me",
    "Apply now", "Apply for this job", "Apply", "Aplicar", "Inscrever-se",
)

# Distinctive final-apply button names -> review (summary page).
_REVIEW_FINAL_NAMES = (
    "Enviar candidatura", "Enviar aplicação", "Confirmar candidatura",
    "Enviar inscrição", "Finalizar inscrição", "Concluir inscrição",
    "Submit application", "Submit",
)

# Bare submit labels -> review ONLY when the page has no editable/question
# controls (a summary page). Exact-match to avoid catching "candidatar-se".
_BARE_SUBMIT_NAMES = frozenset(
    {"enviar", "confirmar", "finalizar", "concluir", "candidatar", "submit"}
)

# Resume/continue button names -> review (in-progress application step).
# Substring match per button; kept disjoint from _START_APPLY_NAMES so a
# "Candidatar-se" nav leftover never outranks an explicit "Continuar".
_CONTINUE_BUTTON_NAMES = (
    "Continuar", "Continuar candidatura", "Continuar inscrição",
    "Retomar", "Retomar candidatura", "Continue", "Resume",
    "Resume application",
)

# Resume phrases in the page text -> review (e.g. Gupy's
# "Olá <nome>, vamos continuar sua candidatura?").
_CONTINUATION_PHRASES = (
    "vamos continuar",
    "continue sua candidatura",
    "retomar candidatura",
    "continue your application",
    "resume your application",
)

# In-progress application flow URL segments (e.g. Gupy's
# /candidates/applications/<id>/steps/<id>/...) -> review lean.
_APPLICATION_FLOW_SEGMENTS = ("applications", "steps")

# Login intents -> auth even when the URL is not an auth path.
_LOGIN_BUTTON_NAMES = frozenset(
    {"entrar", "login", "log in", "sign in", "signin", "acessar"}
)

# Validation/error phrases -> erro (recoverable within budget).
# Natural casing: these constants double as signal labels for the executor.
_ERROR_PHRASES = (
    "Preencha todos os campos",
    "Preencha o campo",
    "Campo obrigatório",
    "Não foi possível",
    "Ocorreu um erro",
    "Algo deu errado",
    "Erro inesperado",
    "Required field",
    "Please fill",
    "An error occurred",
)


# ---------------------------------------------------------------------------
# Node text extraction (handles both ax_tree-normalized and raw CDP nodes)
# ---------------------------------------------------------------------------

def _extract(node_value: Any) -> str:
    """Extract a string value from a plain str or a CDP {"value": x} struct."""
    if isinstance(node_value, dict):
        return str(node_value.get("value") or "")
    return str(node_value) if node_value is not None else ""


def _iter_roles(nodes: Optional[List[Dict[str, Any]]]) -> List[Tuple[str, str]]:
    """Normalize the AX snapshot into a list of (lowercase role, name)."""
    rows: List[Tuple[str, str]] = []
    for node in nodes or []:
        if not isinstance(node, dict):
            continue
        rows.append((_extract(node.get("role")), _extract(node.get("name"))))
    return rows


def _page_text(nodes: Optional[List[Dict[str, Any]]]) -> str:
    """Flatten every role/name/value string into one lowercase page text."""
    chunks: List[str] = []
    for node in nodes or []:
        if not isinstance(node, dict):
            continue
        for key in ("role", "name", "value"):
            value = _extract(node.get(key))
            if value:
                chunks.append(value)
    return "\n".join(chunks).lower()


def _distinct_roles(roles: List[Tuple[str, str]]) -> List[str]:
    """Distinct control roles present, in first-seen order (signal breadcrumbs)."""
    seen: List[str] = []
    for role, _ in roles:
        if role and role not in seen:
            seen.append(role)
    return seen


def _name_contains(name: str, phrases: Tuple[str, ...]) -> Optional[str]:
    """First phrase found as a lowercase substring of the button name, or None."""
    lowered = name.lower().strip()
    for phrase in phrases:
        if phrase.lower() in lowered:
            return phrase
    return None


def _has_any(text: str, phrases: Tuple[str, ...]) -> Optional[str]:
    """First phrase found inside the page text, or None."""
    for phrase in phrases:
        if phrase.lower() in text:
            return phrase
    return None


# ---------------------------------------------------------------------------
# Signal helpers
# ---------------------------------------------------------------------------

def _credential_signal(roles: List[Tuple[str, str]]) -> Optional[str]:
    """Detect email/password/login fields that must never be filled.

    A field labelled "senha"/"password" or a login-named button marks the page
    as auth regardless of the URL (conservative: never risk a credential fill).
    """
    for role, name in roles:
        lowered = name.lower().strip()
        if role in ("textbox", "combobox", "searchbox") and (
            "senha" in lowered or "password" in lowered
        ):
            return "credential_password"
        if role == "button" and lowered in _LOGIN_BUTTON_NAMES:
            return "credential_login_button"
    return None


def _url_segment(url: Optional[str], segments: Tuple[str, ...]) -> Optional[str]:
    """Return the first URL path segment found in segments, or None."""
    if not url:
        return None
    from urllib.parse import urlparse  # local import keeps helpers tiny
    for segment in urlparse(url).path.lower().split("/"):
        if segment and segment in segments:
            return segment
    return None


def _success_url_signal(url: Optional[str]) -> Optional[str]:
    """Return the success path segment of the URL, or None."""
    return _url_segment(url, SUCCESS_URL_SEGMENTS)


# ---------------------------------------------------------------------------
# Public pure classifier
# ---------------------------------------------------------------------------

def classify_page(url: Optional[str],
                  ax_nodes: Optional[List[Dict[str, Any]]]) -> Dict[str, Any]:
    """Classify the current page snapshot into a page kind + matched signals.

    Returns {"page": <PAGE_KINDS>, "signals": [<str>, ...]}. Pure — no browser,
    no filesystem, no network.

    The executor maps the page kind to its next action:
        start   -> next (enter the flow)
        form    -> fill + next
        triagem -> fill (selection) + next
        review  -> confirm_checkpoint (unless confirmed) -> apply-final
        sucesso -> observe + post-submit screenshot -> verdict
        auth    -> STOP (auth_required) — never fill
        erro    -> retry within budget, else INCOMPLETE
    """
    signals: List[str] = []
    roles = _iter_roles(ax_nodes)
    text = _page_text(ax_nodes)

    # 1. auth — the hard stop gate (never filled, regardless of confirmation).
    if is_auth_url(url):
        signals.append("auth_url")
        return {"page": AUTH, "signals": signals}
    credential = _credential_signal(roles)
    if credential is not None:
        signals.append(credential)
        return {"page": AUTH, "signals": signals}

    # 2. sucesso — verified success text or success URL segment.
    success_phrase = _has_any(text, SUCCESS_PHRASES)
    if success_phrase is not None:
        signals.append(f"success_text:{success_phrase}")
        return {"page": SUCESSO, "signals": signals}
    success_segment = _success_url_signal(url)
    if success_segment is not None:
        signals.append(f"success_url:{success_segment}")
        return {"page": SUCESSO, "signals": signals}

    # 3. erro — validation/error alerts (recoverable within the step budget).
    error_phrase = _has_any(text, _ERROR_PHRASES)
    if error_phrase is not None:
        signals.append(f"error_text:{error_phrase}")
        return {"page": ERRO, "signals": signals}

    names = [name for _, name in roles]
    editable = any(role in _EDITABLE_ROLES for role, _ in roles)
    questions = any(role in _QUESTION_ROLES for role, _ in roles)

    # 4. review — summary page (no inputs) with a final apply button, OR an
    # in-progress application resume page (e.g. Gupy's "vamos continuar sua
    # candidatura?" with a "Continuar" button on a /candidates/applications/
    # .../steps/... URL). Resume pages must never be start: start would
    # re-enter the flow and risk a duplicate application.
    final_name = _name_contains(" ".join(names), _REVIEW_FINAL_NAMES)
    bare_submit = any(name.strip().lower() in _BARE_SUBMIT_NAMES for name in names)
    continue_button = next(
        (
            _name_contains(name, _CONTINUE_BUTTON_NAMES)
            for role, name in roles
            if role == "button" and _name_contains(name, _CONTINUE_BUTTON_NAMES) is not None
        ),
        None,
    )
    continuation_phrase = _has_any(text, _CONTINUATION_PHRASES)
    flow_segment = _url_segment(url, _APPLICATION_FLOW_SEGMENTS)
    if not editable and not questions and (
        final_name is not None
        or bare_submit
        or continue_button is not None
        or continuation_phrase is not None
        or flow_segment is not None
    ):
        signals.append(
            f"final_apply_button:{final_name or 'bare-submit'}"
            if (final_name is not None or bare_submit)
            else f"continue_step:{continue_button or continuation_phrase or flow_segment}"
        )
        return {"page": REVIEW, "signals": signals}

    # 5. start — job detail page (no inputs/question groups) with an entry button.
    start_name = _name_contains(" ".join(names), _START_APPLY_NAMES)
    if not editable and not questions and start_name is not None:
        signals.append(f"apply_button:{start_name}")
        return {"page": START, "signals": signals}

    # 6. triagem — screening questions (radio/checkbox/switch groups).
    if questions:
        signals.append("question_group")
        signals.extend(_distinct_roles(roles))
        return {"page": TRIAGEM, "signals": signals}

    # 7. form — editable fields dominate.
    if editable:
        signals.append("editable_fields")
        signals.extend(_distinct_roles(roles))
        return {"page": FORM, "signals": signals}

    # Fallback: unrecognized page — safe erro (recovery or budget, never a
    # blind fill and never a record).
    signals.append("unrecognized_page")
    return {"page": ERRO, "signals": signals}


if __name__ == "__main__":
    import sys
    sys.exit("classify.py is a library module — run classify_test.py instead.")