#!/usr/bin/env python3
"""
auth_guard.py — auth-page detection for the job-portal-browser skill (issue #40).

Pure, self-contained stdlib helpers (re, urllib.parse, typing) that let the bot
detect login/signin/auth pages during FREE navigation and stop immediately.

NEVER-FILL RULE (core of issue #40): on an auth page the bot MUST stop and ask
the user to log in manually. It must NEVER fill email/password credentials.
check_navigation() enforces this: an auth URL always returns ``ok=False`` with
``code="auth_required"``, so a caller can never obtain a step that fills
credentials from an auth page.

This module ships self-contained inside the job-portal-browser skill (mirroring
the semantics of the job-application skill's navigation.py, but NOT importing
it): each skill installs standalone to ``skills/<name>/`` in the bot profile, so
cross-skill imports would break at install time.

Usage:
    from auth_guard import check_navigation, is_auth_url

    result = check_navigation(current_url, job_id=job_id)
    if not result["ok"] and result["error"]["code"] == "auth_required":
        # Stop immediately; ask the user to log in manually at the returned
        # url. Never attempt to fill login fields.
        manual_url = result["error"]["url"]
"""

import re
import urllib.parse
from typing import Dict, Optional, Union

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# NEVER-FILL RULE (issue #40): on auth pages the bot must stop and ask for a
# manual login; it must never fill email/password. check_navigation() enforces
# it — an auth URL always yields ok=False, so no fill step is ever produced.
NEVER_FILL_CREDENTIALS = True

# PT-BR user-facing message (user-facing strings in Portuguese, code/comments
# in English). Asks the user to log in manually; never suggests credentials.
AUTH_REQUIRED_DETAIL = (
    "Página de autenticação detectada. O bot não preenche credenciais: "
    "faça login manualmente nesta página e confirme para continuar."
)

# Only WHOLE path segments are matched (boundary-checked), case-insensitively.
# This mirrors the semantics of navigation.py (issue #38): e.g.
# "/authentication-page" is a single segment that contains "auth" but is not
# equal to it, so it does NOT fire. The Gupy candidates area
# ("/candidates/auth/...") fires through its exact "auth" segment.
_AUTH_SEGMENT_RE = re.compile(r"^(?:login|auth|signin|sign-in)$", re.IGNORECASE)


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------

def is_auth_url(url: Optional[str]) -> bool:
    """Return True when the URL path points to a login/signin/auth page.

    Detection is done on URL path segments only (boundary-checked, whole-segment
    match, case-insensitive) and ignores the query string and fragment, so a job
    slug like ``/jobs/authentication-specialist`` or a ``?next=/login`` tracking
    param never cause a false positive.
    """
    if not url:
        return False
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return False
    if not parsed.scheme and not parsed.netloc:
        # Not a parseable absolute URL.
        return False
    path = parsed.path or ""
    for raw in path.split("/"):
        seg = raw.strip()
        if _AUTH_SEGMENT_RE.match(seg):
            return True
    return False


# ---------------------------------------------------------------------------
# Navigation guard
# ---------------------------------------------------------------------------

def check_navigation(url: Optional[str],
                     job_id: Optional[str] = None) -> Dict[str, object]:
    """Check a navigation step for auth pages; return a structured result.

    Returns ``{"ok": True}`` for normal URLs. For auth pages returns
    ``{"ok": False, "error": {"code": "auth_required", "detail": <PT-BR>,
    "url": <auth url>}}`` — plus ``"job_id"`` inside the error when *job_id* is
    supplied. The NEVER-FILL RULE (``NEVER_FILL_CREDENTIALS``) stands: this
    payload only tells the bot to STOP; it never yields a credential-fill step.
    """
    if not is_auth_url(url):
        return {"ok": True}

    error: Dict[str, object] = {
        "code": "auth_required",
        "detail": AUTH_REQUIRED_DETAIL,
        "url": url,
    }
    if job_id is not None:
        error["job_id"] = job_id
    return {"ok": False, "error": error}