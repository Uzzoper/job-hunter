#!/usr/bin/env python3
"""
job_api_test.py — issue #46 tests for job_api.py (Job Hunter API as the PRIMARY
job source for the Hermes bot).

Covers:
  * resolve_token — precedence (flag > JOBHUNTER_API_TOKEN env > <profile>/api-token.txt),
    file trimming, and the missing-token error dict with a PT-BR service-token setup step.
  * api_list_jobs — real query names on the wire (hasEmail / minScore), X-Bot-Token auth (issue #47),
    401 → {"error": "unauthorized"}.
  * api_trigger_fetch — POST /api/jobs/fetch[/<portal>] path building + 401 mapping.
  * api_get_job — GET /api/jobs/{id} → JobResponse dict (url/title/company per the
    real Spring DTO), 401 → unauthorized, 404 → not_found.
  * pick_jobs_for_apply — orchestration: empty list → trigger fetch (default gupy) →
    re-list → score-desc sorted; fetch_if_empty=False returns [] untouched;
    401 on the first list short-circuits without fetching.

All network I/O is mocked at urllib.request.urlopen (plain unittest.mock).
Plain unittest (pytest-compatible). Stdlib only.

Run:
    python3 job_api_test.py
    python3 -m pytest job_api_test.py
"""

import json
import os
import sys
import tempfile
import unittest
import unittest.mock
import urllib.error
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import job_api  # noqa: E402  (RED phase: module does not exist yet)


def _json_response(payload):
    """Return a MagicMock urlopen response whose body decodes to `payload` JSON."""
    resp = unittest.mock.MagicMock()
    resp.status = 200
    resp.__enter__.return_value.read.return_value = json.dumps(payload).encode("utf-8")
    return resp


def _request_header(req, name):
    """Case-insensitive header lookup.

    urllib normalizes header names in its internal Message (``X-Bot-Token``
    becomes ``X-bot-token``); HTTP header names are case-insensitive on the
    wire, so tests compare lowercased.
    """
    needle = name.lower()
    return next((v for k, v in req.headers.items() if k.lower() == needle), None)


def _http_error(code):
    """Return a urllib HTTPError instance to use as urlopen.side_effect."""
    return urllib.error.HTTPError("http://mock/api", code, "mock error", {}, None)


def _write_token(profile_dir, content="file-tok\n"):
    Path(profile_dir, "api-token.txt").write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# resolve_token
# ---------------------------------------------------------------------------

class ResolveTokenTests(unittest.TestCase):
    """Token precedence: explicit flag > JOBHUNTER_API_TOKEN env > profile file."""

    def test_explicit_flag_beats_env(self):
        with unittest.mock.patch.dict(os.environ, {"JOBHUNTER_API_TOKEN": "env-tok"}):
            self.assertEqual(
                job_api.resolve_token("flag-tok", profile_dir="/nonexistent"),
                "flag-tok",
            )

    def test_flag_beats_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_token(tmp)
            self.assertEqual(job_api.resolve_token("flag-tok", profile_dir=tmp), "flag-tok")

    def test_env_beats_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_token(tmp)
            with unittest.mock.patch.dict(os.environ, {"JOBHUNTER_API_TOKEN": "env-tok"}):
                self.assertEqual(job_api.resolve_token(None, profile_dir=tmp), "env-tok")

    def test_blank_flag_falls_through_to_env(self):
        with unittest.mock.patch.dict(os.environ, {"JOBHUNTER_API_TOKEN": "env-tok"}):
            self.assertEqual(
                job_api.resolve_token("   ", profile_dir="/nonexistent"), "env-tok"
            )

    def test_file_token_used_when_no_flag_or_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_token(tmp)
            with unittest.mock.patch.dict(os.environ, {}, clear=True):
                self.assertEqual(job_api.resolve_token(None, profile_dir=tmp), "file-tok")

    def test_file_token_is_trimmed(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_token(tmp, "  file-tok  \n")
            self.assertEqual(job_api.resolve_token(None, profile_dir=tmp), "file-tok")

    def test_missing_token_returns_error_dict(self):
        with unittest.mock.patch.dict(os.environ, {}, clear=True):
            result = job_api.resolve_token(None, profile_dir="/nonexistent-empty")
            self.assertIsInstance(result, dict)
            self.assertEqual(result["error"], "missing_api_token")

    def test_missing_token_detail_is_pt_br_with_service_token_step(self):
        with unittest.mock.patch.dict(os.environ, {}, clear=True):
            result = job_api.resolve_token(None, profile_dir="/nonexistent-empty")
            detail = result.get("detail", "")
            self.assertTrue(detail)
            self.assertIn("Token", detail)
            self.assertIn("BOT_SERVICE_API_KEY", detail)
            self.assertIn("bot.service.api-key", detail)
            self.assertIn("JOBHUNTER_API_TOKEN", detail)

    def test_default_profile_dir_is_bot_profile(self):
        expected = os.path.join(".hermes", "profiles", "jobhunter-bot")
        self.assertTrue(str(job_api.DEFAULT_PROFILE_DIR).endswith(expected))

    def test_token_file_and_env_constants(self):
        self.assertEqual(job_api.TOKEN_FILE_NAME, "api-token.txt")
        self.assertEqual(job_api.API_TOKEN_ENV, "JOBHUNTER_API_TOKEN")


# ---------------------------------------------------------------------------
# api_list_jobs
# ---------------------------------------------------------------------------

class ListJobsTests(unittest.TestCase):
    """GET /api/jobs — real query names (hasEmail/minScore) + X-Bot-Token auth."""

    @unittest.mock.patch("urllib.request.urlopen")
    def test_sends_bot_service_token_and_default_omits_has_email(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([{"id": 1, "title": "x"}])
        result = job_api.api_list_jobs("http://localhost:8080", "tok-123")
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(_request_header(req, "X-Bot-Token"), "tok-123")
        self.assertIsNone(_request_header(req, "Authorization"))
        self.assertIn("api/jobs", req.full_url)
        self.assertNotIn("hasEmail", req.full_url)
        self.assertEqual(result, [{"id": 1, "title": "x"}])

    @unittest.mock.patch("urllib.request.urlopen")
    def test_has_email_true_sent_as_true(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([])
        job_api.api_list_jobs("http://localhost:8080", "t", has_email=True)
        req = mock_urlopen.call_args[0][0]
        self.assertIn("hasEmail=true", req.full_url)
        self.assertNotIn("hasEmail=false", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_min_score_maps_to_min_score_param(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([])
        job_api.api_list_jobs("http://localhost:8080", "t", min_score=70, has_email=True)
        req = mock_urlopen.call_args[0][0]
        self.assertIn("minScore=70", req.full_url)
        self.assertIn("hasEmail=true", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_min_score_none_omits_param(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([])
        job_api.api_list_jobs("http://localhost:8080", "t")
        req = mock_urlopen.call_args[0][0]
        self.assertNotIn("minScore", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_has_email_false_sent_as_false(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([])
        job_api.api_list_jobs("http://localhost:8080", "t", has_email=False)
        req = mock_urlopen.call_args[0][0]
        self.assertIn("hasEmail=false", req.full_url)
        self.assertNotIn("hasEmail=true", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_401_returns_unauthorized(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)
        self.assertEqual(
            job_api.api_list_jobs("http://localhost:8080", "t"),
            {"error": "unauthorized"},
        )

    @unittest.mock.patch("urllib.request.urlopen")
    def test_unexpected_http_status_returns_api_error(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(500)
        result = job_api.api_list_jobs("http://localhost:8080", "t")
        self.assertEqual(result["error"], "api_error")
        self.assertIn("500", result.get("detail", ""))


# ---------------------------------------------------------------------------
# api_trigger_fetch
# ---------------------------------------------------------------------------

class TriggerFetchTests(unittest.TestCase):
    """POST /api/jobs/fetch[/<portal>] — path building + 401 mapping."""

    @unittest.mock.patch("urllib.request.urlopen")
    def test_default_path_no_portal(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({"totalFetched": 3, "totalSaved": 2})
        result = job_api.api_trigger_fetch("http://localhost:8080", "t")
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(_request_header(req, "X-Bot-Token"), "t")
        self.assertIsNone(_request_header(req, "Authorization"))
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/fetch")
        self.assertEqual(result["totalFetched"], 3)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_portal_gupy_appended_to_path(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({})
        job_api.api_trigger_fetch("http://localhost:8080", "t", portal="gupy")
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/fetch/gupy")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_portal_infojobs_appended_to_path(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({})
        job_api.api_trigger_fetch("http://localhost:8080", "t", portal="infojobs")
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/fetch/infojobs")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_401_returns_unauthorized(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)
        self.assertEqual(
            job_api.api_trigger_fetch("http://localhost:8080", "t", portal="gupy"),
            {"error": "unauthorized"},
        )


# ---------------------------------------------------------------------------
# api_get_job
# ---------------------------------------------------------------------------

class GetJobTests(unittest.TestCase):
    """GET /api/jobs/{id} parsing against the real JobResponse DTO fields."""

    REAL_JOB = {
        "id": 7,
        "title": "Desenvolvedor Java Pleno",
        "company": "Acme Corp",
        "url": "https://jobs.gupy.io/jobs/777-java-pleno",
        "description": "Backend Java 21, Spring Boot",
        "postedAt": "2026-09-01",
        "source": "gupy",
        "contactEmail": "rh@acme.example",
    }

    @unittest.mock.patch("urllib.request.urlopen")
    def test_url_includes_job_id(self, mock_urlopen):
        mock_urlopen.return_value = _json_response(self.REAL_JOB)
        job_api.api_get_job("http://localhost:8080/", "t", 42)
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/42")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_returns_job_dict_with_real_dto_fields(self, mock_urlopen):
        mock_urlopen.return_value = _json_response(self.REAL_JOB)
        job = job_api.api_get_job("http://localhost:8080", "t", 7)
        self.assertEqual(job["id"], 7)
        self.assertEqual(job["url"], self.REAL_JOB["url"])
        self.assertEqual(job["title"], self.REAL_JOB["title"])
        self.assertEqual(job["company"], self.REAL_JOB["company"])
        self.assertEqual(job["contactEmail"], self.REAL_JOB["contactEmail"])

    @unittest.mock.patch("urllib.request.urlopen")
    def test_401_returns_unauthorized(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)
        self.assertEqual(
            job_api.api_get_job("http://localhost:8080", "t", 1),
            {"error": "unauthorized"},
        )

    @unittest.mock.patch("urllib.request.urlopen")
    def test_404_returns_not_found(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(404)
        self.assertEqual(
            job_api.api_get_job("http://localhost:8080", "t", 999),
            {"error": "not_found"},
        )


# ---------------------------------------------------------------------------
# api_record_applied (issue #48 — backend canonical record)
# ---------------------------------------------------------------------------

class RecordAppliedTests(unittest.TestCase):
    """POST /api/jobs/{id}/applied — send the applied marker upstream.

    Follows the existing conventions: X-Bot-Token header, JSON echo response,
    401 → unauthorized, 404 → not_found, transport → api_error. Stdlib urllib
    only, all network I/O mocked at urllib.request.urlopen.
    """

    @unittest.mock.patch("urllib.request.urlopen")
    def test_posts_to_applied_path_with_token(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({"jobId": 7, "status": "applied"})
        result = job_api.api_record_applied("http://localhost:8080", "tok-1", 7)
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/7/applied")
        self.assertEqual(_request_header(req, "X-Bot-Token"), "tok-1")
        self.assertIsNone(_request_header(req, "Authorization"))
        self.assertEqual(result, {"jobId": 7, "status": "applied"})

    @unittest.mock.patch("urllib.request.urlopen")
    def test_returns_echoed_applied_marker(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({"jobId": 7, "status": "applied"})
        result = job_api.api_record_applied("http://localhost:8080", "t", 7)
        self.assertEqual(result["jobId"], 7)
        self.assertEqual(result["status"], "applied")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_sends_empty_json_object_body(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({"jobId": 7, "status": "applied"})
        job_api.api_record_applied("http://localhost:8080", "t", 7)
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.data, b"{}")
        self.assertEqual(_request_header(req, "Content-Type"), "application/json")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_base_url_with_trailing_slash_still_one_slash(self, mock_urlopen):
        mock_urlopen.return_value = _json_response({"jobId": 7, "status": "applied"})
        job_api.api_record_applied("http://localhost:8080/", "t", 7)
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://localhost:8080/api/jobs/7/applied")
        self.assertIn("api/jobs/7/applied", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_401_returns_unauthorized(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)
        self.assertEqual(
            job_api.api_record_applied("http://localhost:8080", "t", 7),
            {"error": "unauthorized"},
        )

    @unittest.mock.patch("urllib.request.urlopen")
    def test_404_returns_not_found(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(404)
        self.assertEqual(
            job_api.api_record_applied("http://localhost:8080", "t", 999),
            {"error": "not_found"},
        )

    @unittest.mock.patch("urllib.request.urlopen")
    def test_transport_error_returns_api_error(self, mock_urlopen):
        mock_urlopen.side_effect = OSError("connection refused")
        result = job_api.api_record_applied("http://localhost:8080", "t", 7)
        self.assertEqual(result["error"], "api_error")
        self.assertIn("connection refused", result.get("detail", ""))

    @unittest.mock.patch("urllib.request.urlopen")
    def test_timeout_returns_api_error(self, mock_urlopen):
        mock_urlopen.side_effect = TimeoutError("timed out")
        result = job_api.api_record_applied("http://localhost:8080", "t", 7)
        self.assertEqual(result["error"], "api_error")

    @unittest.mock.patch("urllib.request.urlopen")
    def test_timeout_default_is_10(self, mock_urlopen):
        mock_urlopen.side_effect = OSError("fail")
        job_api.api_record_applied("http://localhost:8080", "t", 7)
        _, kwargs = mock_urlopen.call_args
        self.assertEqual(kwargs.get("timeout"), 10)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_custom_timeout_forwarded(self, mock_urlopen):
        mock_urlopen.side_effect = OSError("fail")
        job_api.api_record_applied("http://localhost:8080", "t", 7, timeout=25)
        _, kwargs = mock_urlopen.call_args
        self.assertEqual(kwargs.get("timeout"), 25)


# ---------------------------------------------------------------------------
# pick_jobs_for_apply (orchestrator)
# ---------------------------------------------------------------------------

class PickJobsTests(unittest.TestCase):
    """List → empty → trigger fetch (default gupy) → re-list → score-desc sort."""

    def _job(self, jid, score=None):
        job = {
            "id": jid,
            "title": f"Job {jid}",
            "company": "Acme",
            "url": f"https://jobs.gupy.io/jobs/{jid}",
            "source": "gupy",
            "contactEmail": "rh@acme.example",
        }
        if score is not None:
            job["matchScore"] = score
        return job

    @unittest.mock.patch("urllib.request.urlopen")
    def test_ordering_by_score_desc(self, mock_urlopen):
        jobs = [self._job(1, 30), self._job(2, 80), self._job(3)]
        mock_urlopen.return_value = _json_response(jobs)
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t")
        self.assertEqual([j["id"] for j in result], [2, 1, 3])
        # Non-empty list: exactly one GET, no fetch trigger.
        self.assertEqual(mock_urlopen.call_count, 1)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_missing_score_sorted_last(self, mock_urlopen):
        jobs = [self._job(1, 60), self._job(2), self._job(3, 90)]
        mock_urlopen.return_value = _json_response(jobs)
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t")
        self.assertEqual([j["id"] for j in result], [3, 1, 2])

    @unittest.mock.patch("urllib.request.urlopen")
    def test_empty_triggers_fetch_default_gupy_then_relist(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _json_response([]),                                   # 1st list: empty
            _json_response({"totalFetched": 2, "totalSaved": 2}),  # fetch (gupy)
            _json_response([self._job(1, 50), self._job(2, 90)]),  # re-list
        ]
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t")
        self.assertEqual(mock_urlopen.call_count, 3)
        req0 = mock_urlopen.call_args_list[0][0][0]
        self.assertEqual(req0.get_method(), "GET")
        self.assertIn("api/jobs", req0.full_url)
        req1 = mock_urlopen.call_args_list[1][0][0]
        self.assertEqual(req1.get_method(), "POST")
        self.assertEqual(req1.full_url, "http://localhost:8080/api/jobs/fetch/gupy")
        req2 = mock_urlopen.call_args_list[2][0][0]
        self.assertEqual(req2.get_method(), "GET")
        self.assertEqual([j["id"] for j in result], [2, 1])

    @unittest.mock.patch("urllib.request.urlopen")
    def test_custom_fetch_portal_used(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _json_response([]),
            _json_response({}),
            _json_response([]),
        ]
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t", portal="infojobs")
        req1 = mock_urlopen.call_args_list[1][0][0]
        self.assertEqual(req1.full_url, "http://localhost:8080/api/jobs/fetch/infojobs")
        self.assertEqual(result, [])

    @unittest.mock.patch("urllib.request.urlopen")
    def test_fetch_disabled_returns_empty_without_fetch(self, mock_urlopen):
        mock_urlopen.return_value = _json_response([])
        result = job_api.pick_jobs_for_apply(
            "http://localhost:8080", "t", fetch_if_empty=False
        )
        self.assertEqual(result, [])
        self.assertEqual(mock_urlopen.call_count, 1)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_unauthorized_first_list_short_circuits(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t")
        self.assertEqual(result, {"error": "unauthorized"})
        # No fetch call happens after an auth failure.
        self.assertEqual(mock_urlopen.call_count, 1)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_filters_forwarded_to_both_lists(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _json_response([]),
            _json_response({}),
            _json_response([self._job(9, 10)]),
        ]
        job_api.pick_jobs_for_apply(
            "http://localhost:8080", "t", min_score=60, has_email=False
        )
        for call_idx in (0, 2):
            req = mock_urlopen.call_args_list[call_idx][0][0]
            self.assertIn("minScore=60", req.full_url)
            self.assertIn("hasEmail=false", req.full_url)

    @unittest.mock.patch("urllib.request.urlopen")
    def test_fetch_error_returned_cleanly(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _json_response([]),
            _http_error(401),
        ]
        result = job_api.pick_jobs_for_apply("http://localhost:8080", "t")
        self.assertEqual(result, {"error": "unauthorized"})


if __name__ == "__main__":
    unittest.main()