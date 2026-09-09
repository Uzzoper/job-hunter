#!/usr/bin/env python3
"""
job_api.py — Job Hunter API client for the Hermes bot (issue #46).

The Job Hunter REST API is the PRIMARY job source for applications. This module
talks to the Spring Boot backend using stdlib only (`urllib`, `json`, `os`,
`pathlib`, `typing`). The bot authenticates with the static service token
(issue #47): no credentials are ever stored in this repo — the secret is
generated ONCE by the human, configured on the backend as ``bot.service.api-key``
(BOT_SERVICE_API_KEY), and the SAME value is kept in bot memory
(<profile>/api-token.txt or the JOBHUNTER_API_TOKEN env var).

Functions
---------
resolve_token(api_token=None, profile_dir=None)
    flag > JOBHUNTER_API_TOKEN env > <profile_dir>/api-token.txt.
    Missing → {"error": "missing_api_token", "detail": <pt-br login+save step>}.

api_list_jobs(base_url, token, min_score=None, has_email=True, timeout=10)
    GET <base_url>/api/jobs with the REAL query names (hasEmail, minScore).
    401 → {"error": "unauthorized"}.

api_trigger_fetch(base_url, token, portal=None, timeout=60)
    POST <base_url>/api/jobs/fetch[/<portal>].

api_get_job(base_url, token, job_id, timeout=10)
    GET <base_url>/api/jobs/<job_id> → JobResponse dict (id, title, company,
    url, description, postedAt, source, contactEmail).

api_record_applied(base_url, token, job_id, timeout=10)
    POST <base_url>/api/jobs/<job_id>/applied with an empty JSON body →
    echoes {jobId, status}; idempotent backend canonical record.

pick_jobs_for_apply(base_url, token, ...)
    Orchestrator: list → empty → trigger fetch (default gupy) → re-list →
    score-desc sorted.

Design notes
------------
* Every request carries ``X-Bot-Token: <token>`` (the service token is THE bot
  auth method — no Bearer, no user login). A 401 anywhere maps to the
  machine-readable ``{"error": "unauthorized"}`` the bot can surface.
* All functions return either their payload type (str / list / dict) or an
  error dict with an ``"error"`` key — callers check ``isinstance(result, dict)
  and "error" in result``.
* Wire query names follow the Spring controller exactly: ``hasEmail`` (Job
  Controller ``@RequestParam Boolean hasEmail``) and ``minScore`` (score filter
  per the API roadmap). The backend ignores ``minScore`` until the filter is
  implemented — harmless, and it makes the bot forward-compatible.
"""

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Union

# ---------------------------------------------------------------------------
# Token resolution
# ---------------------------------------------------------------------------

# Bot memory convention (same profile the Hermes bot uses for memails/applications).
DEFAULT_PROFILE_DIR = Path.home() / ".hermes" / "profiles" / "jobhunter-bot"
TOKEN_FILE_NAME = "api-token.txt"
API_TOKEN_ENV = "JOBHUNTER_API_TOKEN"

# PT-BR user-facing message (issue #46 — user-facing strings in Portuguese).
_MISSING_TOKEN_DETAIL = (
    "Token da API do Job Hunter não encontrado. Gere um segredo de serviço "
    "uma vez (ex.: openssl rand -hex 24), configure-o no backend como "
    "bot.service.api-key (variável BOT_SERVICE_API_KEY) com "
    "bot.service.owner-user-id, e salve o MESMO valor em "
    "<profile-dir>/api-token.txt ou na variável de ambiente "
    "JOBHUNTER_API_TOKEN. Nenhuma credencial é armazenada no repositório."
)


def resolve_token(api_token: Optional[str] = None,
                  profile_dir: Optional[str] = None) -> Union[str, Dict[str, str]]:
    """Resolve the Job Hunter API service token (issue #47, registered once).

    The backend derives authority from ``bot.service.api-key`` matching the
    ``X-Bot-Token`` header exactly, and the same secret is stored here in bot
    memory. Precedence:
        1. explicit *api_token* flag,
        2. ``JOBHUNTER_API_TOKEN`` environment variable,
        3. ``<profile_dir>/api-token.txt`` (default
           ``~/.hermes/profiles/jobhunter-bot`` — the bot memory profile).

    Returns the token string, or an error dict on missing:
        {"error": "missing_api_token", "detail": <pt-br login + save step>}
    """
    flag = (api_token or "").strip()
    if flag:
        return flag
    env_token = os.environ.get(API_TOKEN_ENV, "").strip()
    if env_token:
        return env_token
    base = Path(profile_dir) if profile_dir else DEFAULT_PROFILE_DIR
    token_path = base / TOKEN_FILE_NAME
    if token_path.is_file():
        try:
            file_token = token_path.read_text(encoding="utf-8").strip()
        except OSError:
            file_token = ""
        if file_token:
            return file_token
    detail = _MISSING_TOKEN_DETAIL.replace("<profile-dir>", str(base))
    return {"error": "missing_api_token", "detail": detail}


# ---------------------------------------------------------------------------
# HTTP plumbing
# ---------------------------------------------------------------------------

def _request_json(method: str, url: str, token: str, timeout: int = 10,
                  data_bytes: Optional[bytes] = None) -> Any:
    """Issues a service-token-authenticated HTTP request (issue #47) and returns the JSON payload.

    Returns the parsed JSON on success (list for the list endpoint, dict for
    detail/fetch endpoints). Never raises for HTTP errors: it maps them to an
    error dict instead:

        * HTTP 401 → ``{"error": "unauthorized"}``
        * HTTP 404 → ``{"error": "not_found"}``
        * other    → ``{"error": "api_error", "detail": <msg>}``

    Transport failures (connection refused, timeout, DNS) also map to
    ``api_error`` so the CLI can always print clean JSON.
    """
    headers = {
        "X-Bot-Token": token,
        "Accept": "application/json",
    }
    req = urllib.request.Request(url, data=data_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            return {"error": "unauthorized"}
        if exc.code == 404:
            return {"error": "not_found"}
        return {"error": "api_error", "detail": f"HTTP {exc.code} from {url}"}
    except Exception as exc:  # transport-level errors keep stdout JSON-only
        return {"error": "api_error", "detail": f"{type(exc).__name__}: {exc}"}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {"error": "api_error", "detail": "invalid JSON response"}


def _should_error_short_circuit(payload: Any) -> bool:
    """True when *payload* is an error dict (has an ``"error"`` key)."""
    return isinstance(payload, dict) and "error" in payload


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

def api_list_jobs(base_url: str, token: str, min_score: Optional[int] = None,
                  has_email: Optional[bool] = None,
                  timeout: int = 10) -> Union[List[Dict[str, Any]],
                                              Dict[str, Any]]:
    """GET <base_url>/api/jobs — the primary job source (issue #46).

    Uses the REAL query names exposed by JobController.getAllJobs:

        * ``hasEmail`` — contact-email presence filter (``@RequestParam
          Boolean hasEmail``); sent whenever *has_email* is not None.
          Default ``None`` omits the filter (list ALL jobs); pass True
          only on explicit request ("only jobs with email").
        * ``minScore`` — score filter (API roadmap); sent only when
          *min_score* is not None.

    Returns the list of JobResponse dicts, or an error dict (401 →
    ``{"error": "unauthorized"}``).
    """
    params: Dict[str, str] = {}
    if has_email is not None:
        params["hasEmail"] = "true" if has_email else "false"
    if min_score is not None:
        params["minScore"] = str(int(min_score))
    url = base_url.rstrip("/") + "/api/jobs"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    payload = _request_json("GET", url, token, timeout=timeout)
    if _should_error_short_circuit(payload):
        return payload
    if not isinstance(payload, list):
        return {"error": "api_error", "detail": "unexpected response: expected a list"}
    return payload


def api_trigger_fetch(base_url: str, token: str, portal: Optional[str] = None,
                      timeout: int = 60) -> Union[Dict[str, Any], List[Any]]:
    """POST <base_url>/api/jobs/fetch[/<portal>] — trigger a scraping cycle.

    *portal* is None (all providers) or one of ``gupy``, ``linkedin``,
    ``infojobs``. Returns the FetchResultResponse dict (totalFetched,
    totalSaved, totalWithEmail, perProvider) or an error dict (401 →
    ``{"error": "unauthorized"}``).
    """
    url = base_url.rstrip("/") + "/api/jobs/fetch"
    if portal:
        url += "/" + str(portal).strip().lower()
    return _request_json("POST", url, token, timeout=timeout, data_bytes=b"")


def api_get_job(base_url: str, token: str, job_id: int,
                timeout: int = 10) -> Union[Dict[str, Any], List[Any]]:
    """GET <base_url>/api/jobs/<job_id> — one JobResponse dict.

    The dict carries the real Spring DTO fields: id, title, company, url,
    description, postedAt, source, contactEmail. Errors: 401 →
    ``unauthorized``, 404 → ``not_found``, otherwise ``api_error``.
    """
    url = f"{base_url.rstrip('/')}/api/jobs/{job_id}"
    payload = _request_json("GET", url, token, timeout=timeout)
    if _should_error_short_circuit(payload):
        return payload
    if not isinstance(payload, dict):
        return {"error": "api_error", "detail": "unexpected response: expected a job object"}
    return payload


def api_record_applied(base_url: str, token: str, job_id: int,
                       timeout: int = 10) -> Union[Dict[str, Any], List[Any]]:
    """POST <base_url>/api/jobs/<job_id>/applied — backend canonical record.

    Sends an empty JSON ``{}`` body (the applied marker) with the X-Bot-Token
    header. The backend is idempotent: a repeat apply returns the existing
    marker instead of a conflict.

    Returns the echoed ``{jobId, status}`` dict on success. Errors: 401 →
    ``unauthorized``, 404 → ``not_found``, transport/HTTP → ``api_error``
    (the caller decides whether to surface the backend failure).
    """
    url = f"{base_url.rstrip('/')}/api/jobs/{job_id}/applied"
    data = b"{}"
    headers = {
        "X-Bot-Token": token,
        "Accept": "application/json",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            return {"error": "unauthorized"}
        if exc.code == 404:
            return {"error": "not_found"}
        return {"error": "api_error", "detail": f"HTTP {exc.code} from {url}"}
    except Exception as exc:  # transport-level errors keep stdout JSON-only
        return {"error": "api_error", "detail": f"{type(exc).__name__}: {exc}"}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {"error": "api_error", "detail": "invalid JSON response"}


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------

def _job_score(job: Any) -> float:
    """Score of a job dict for descending sort; missing/invalid score → 0."""
    if not isinstance(job, dict):
        return 0.0
    score = job.get("matchScore")
    if isinstance(score, (int, float)) and not isinstance(score, bool):
        return float(score)
    return 0.0


def sort_jobs_by_score(jobs: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Return *jobs* sorted by ``matchScore`` descending (missing score = 0)."""
    return sorted(jobs, key=_job_score, reverse=True)


def pick_jobs_for_apply(base_url: str, token: str,
                        min_score: Optional[int] = None,
                        has_email: Optional[bool] = None,
                        fetch_if_empty: bool = True,
                        portal: str = "gupy",
                        timeout: int = 10,
                        fetch_timeout: int = 60) -> Union[List[Dict[str, Any]],
                                                          Dict[str, Any]]:
    """Orchestrate the API-first job picker for the apply bot.

    Flow:
        1. GET /api/jobs (optional hasEmail + minScore filters) — the PRIMARY source.
           Default lists ALL jobs; hasEmail applies only on explicit request.
        2. If the list is empty AND *fetch_if_empty* (default True): trigger a
           scrape via POST /api/jobs/fetch/<portal> (portal defaults to gupy,
           matching the apply flow's primary portal).
        3. Re-list and return the jobs score-desc sorted (by ``matchScore``,
           missing score = 0).

    Returns the sorted list of JobResponse dicts, or an error dict (any step
    failing — e.g. 401 → ``{"error": "unauthorized"}``) that the caller prints
    as clean JSON with exit code 1.
    """
    jobs = api_list_jobs(base_url, token, min_score=min_score,
                         has_email=has_email, timeout=timeout)
    if _should_error_short_circuit(jobs):
        return jobs

    if not jobs and fetch_if_empty:
        fetch = api_trigger_fetch(base_url, token, portal=portal,
                                  timeout=fetch_timeout)
        if _should_error_short_circuit(fetch):
            return fetch
        jobs = api_list_jobs(base_url, token, min_score=min_score,
                             has_email=has_email, timeout=timeout)
        if _should_error_short_circuit(jobs):
            return jobs

    if not isinstance(jobs, list):
        return {"error": "api_error", "detail": "unexpected response from the jobs endpoint"}
    return sort_jobs_by_score(jobs)


if __name__ == "__main__":
    raise SystemExit(
        "job_api.py is a library module for the job-application skill; "
        "import it from apply.py or the bot instead of running it directly."
    )