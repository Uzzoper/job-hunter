#!/usr/bin/env python3
"""
navigation.py — navigation auth/loop guard for the job-application skill (issue #38).

Pure stdlib-only helpers that let the bot/executor detect when it is stuck in a
login/auth redirect loop during a Gupy application, and surface a clear,
machine-readable error with a manual link + screenshot hint instead of silently
retrying forever.

Design: stateless. The bot passes visited-URL history and the current URL on each
invocation; this module never touches a browser, a filesystem, or the network.

Usage:
    from navigation import is_auth_url, NavigationGuard
    guard = NavigationGuard(job_url=JOB_URL, visited_urls=[...])
    result = guard.record(CURRENT_URL)
"""

import re
import urllib.parse
from typing import Dict, List, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

MAX_VISITS = 3

# Substrings that identify a login/auth/signin page. Only whole path segments
# are matched (boundary-checked), so e.g. "/authentication-page" does not fire
# on "auth". We match case-insensitively.
_AUTH_SEGMENTS = ("login", "auth", "signin", "sign-in")


def _iter_path_segments(path: str):
    """Yield the non-empty, lowercased segments of a URL path."""
    for raw in path.split("/"):
        seg = raw.strip()
        if seg:
            yield seg.lower()


def is_auth_url(url: Optional[str]) -> bool:
    """Return True when the URL path points to a login/auth/signin page.

    Detection is done on URL path segments only (not the whole string), so a
    job slug like ``/jobs/authentication-specialist`` won't be a false positive.
    Matching is case-insensitive.
    """
    if not url:
        return False
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return False
    if not parsed.scheme and not parsed.netloc:
        # Not a parseable URL at all.
        return False
    path = parsed.path or ""
    for seg in _iter_path_segments(path):
        if seg in _AUTH_SEGMENTS:
            return True
    return False


def normalize_url(url: Optional[str]) -> Optional[str]:
    """Normalize a URL for stable equality comparison.

    Choices (documented in the spec at docs/specs/job-application-auth-loop.md):
      - lower-case scheme and netloc (host), since hosts are case-insensitive
      - strip the query string, because visited-list comparison should treat
        ``?step=1`` / ``?step=2`` as the same page (a fragment of a loop)
      - strip the fragment
      - strip trailing slashes from the path

    Query stripping is intentional: during a multi-step apply the bot may append
    tracking/step parameters that change every visit while still landing on the
    same page. We want that to count as the same URL for loop detection.
    """
    if not url:
        return None
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return url
    scheme = parsed.scheme.lower()
    netloc = parsed.netloc.lower()
    path = parsed.path or ""
    while path.endswith("/"):
        path = path[:-1]
    if scheme and netloc:
        return f"{scheme}://{netloc}{path}"
    # Relative or network-path reference without a host: keep path only.
    return path or None


class NavigationGuard:
    """Track visited URLs and report auth/loop conditions.

    The guard is constructed with the original job URL (used as ``manual_url``
    so the human can complete the application by hand) and an optional list of
    previously visited URLs (comma-joined at the CLI boundary). Call ``record()``
    with the CURRENT url each time the bot navigates.
    """

    def __init__(self, job_url: Optional[str] = None,
                 visited_urls: Optional[List[str]] = None):
        self.job_url = job_url or ""
        self._counts: Dict[str, int] = {}
        for url in visited_urls or []:
            self.record(url)

    def record(self, url: Optional[str]) -> Dict[str, object]:
        """Register a navigation and return the structured guard result.

        Result shape:
            {
                "loop_detected": bool,
                "auth_detected": bool,
                "manual_url": str,
                "visits": int,          # visits to the CURRENT url (normalized)
            }

        Auth detection takes priority: an auth URL always returns
        ``auth_detected=True`` regardless of the visit count (the bot should
        stop and ask the human rather than keep looping).
        """
        norm = normalize_url(url)
        auth = is_auth_url(url)

        visits = 1
        if norm is not None and not auth:
            # Only count real navigations toward the loop threshold; an auth
            # URL short-circuits before we record it (avoid futile counting).
            visits = self._counts.get(norm, 0) + 1
            self._counts[norm] = visits

        loop = (norm is not None and not auth and visits >= MAX_VISITS)

        return {
            "loop_detected": loop,
            "auth_detected": auth,
            "manual_url": self.job_url,
            "visits": visits,
        }


def guard_from_cli(job_url: Optional[str],
                   current_url: Optional[str],
                   visited_urls: Optional[str]) -> Dict[str, object]:
    """One-shot guard check driven by CLI string arguments.

    ``visited_urls`` may be None or a comma-separated string of URLs (from the
    ``--visited-urls`` flag). The current URL is separate and never counted
    against the loop threshold for the *same* invocation — it is compared
    against the seeded history.
    """
    history: List[str] = []
    if visited_urls:
        history = [u for u in (seg.strip() for seg in visited_urls.split(",")) if u]

    guard = NavigationGuard(job_url=job_url, visited_urls=history)

    if current_url and is_auth_url(current_url):
        return {
            "block": "auth_required",
            "loop_detected": False,
            "auth_detected": True,
            "manual_url": job_url or "",
            "norm_url": normalize_url(current_url),
        }

    # Loop detection operates on the *combined* view: the seeded history plus,
    # when present, the current URL (which participates in the count too).
    # Determine the max visit count across all normalized visited URLs.
    max_visits = max(guard._counts.values(), default=0) if guard._counts else 0
    if current_url:
        norm_cur = normalize_url(current_url)
        if norm_cur is not None and not is_auth_url(current_url):
            # Normalize again to reuse NavigationGuard's counting semantics.
            result = guard.record(current_url)
            max_visits = max(max_visits, result["visits"])
    if max_visits >= MAX_VISITS:
        return {
            "block": "navigation_loop",
            "loop_detected": True,
            "auth_detected": False,
            "manual_url": job_url or "",
            "visits": max_visits,
        }

    return {
        "block": None,
        "loop_detected": False,
        "auth_detected": False,
        "manual_url": job_url or "",
    }
