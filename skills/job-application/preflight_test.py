#!/usr/bin/env python3
"""
preflight_test.py — issue #72 tests for preflight.py (deterministic pre-intent gate).

Spec: docs/specs/deterministic-apply-runbook.md §3.1 and §4. preflight.py is a
NEW stdlib-only module (same style as apply.py) that runs BEFORE any intent is
emitted and checks, IN ORDER:

  1. chromium_running      — Chromium for the DEDICATED profile (the
                             user-data-dir) is running; any chromium alone is
                             NOT proof;
  2. cdp_endpoint          — the CDP endpoint from the profile config answers
                             GET <cdp_url>/json;
  3. browser_tool_attached — the configured browser tool is attached to THAT
                             SAME CDP endpoint (tab list matches; a listening
                             port alone is NOT proof);
  4. gupy_session          — the Gupy session is valid (authenticated marker
                             present, and no /candidates/auth redirect).

The bot proceeds only on a full pass; on failure it stops and reports the
failing check VERBATIM — no improvisation, no fallback browsers, no scripts.

Plain unittest (pytest-compatible), no Spring, no network: every check function
receives injected fakes (process listing, CDP HTTP responses, browser-tool tab
list, page snapshot) exactly like the existing suites (verdict_test.py,
apply_test.py). run() is exercised in-process with redirect_stdout and the four
check functions mocked — the CLI-level tests battle-test orchestration, exit
codes and error SHAPES; the check-level tests battle-test each rule's logic
with real fakes (including the port-open-but-detached and auth-redirect cases).

Contract the GREEN module must implement (documented here so the RED run fails
only on the missing import and nothing else is ambiguous):

  Constants:
    DEFAULT_CDP_URL        = "http://localhost:9222"
    DEFAULT_USER_DATA_DIR  = "~/.chromium-profile-cdp"
    ERROR_CHROMIUM_NOT_RUNNING   = "chromium_not_running"
    ERROR_CDP_UNREACHABLE        = "cdp_unreachable"
    ERROR_BROWSER_TOOL_DETACHED  = "browser_tool_detached"
    ERROR_GUPY_SESSION_INVALID   = "gupy_session_invalid"
    ERROR_CONFIG_INVALID         = "invalid_config"

  Checks (each returns {"ok": True, ...facts} or else
  {"ok": False, "error": <code>, "detail": <msg>}):
    check_chromium_running(user_data_dir, list_procs=None) -> dict
    check_cdp_endpoint(cdp_url, fetch=None) -> dict
    check_browser_tool_attached(cdp_url, tool_tabs, fetch=None) -> dict
    check_gupy_session(page_state) -> dict

  Orchestration:
    run(argv: Optional[List[str]] = None) -> int   # prints JSON, returns exit
        0 = full pass, 1 = first failing check reported verbatim, 2 = usage
    Failure shape (short-circuits at the first failing check):
      {"ok": false, "check": <name>, "error": <code>, "detail": <verbatim>,
       "checks": {<evaluated checks only, spec order>}}
    Success shape:
      {"ok": true, "checks": {chromium_running, cdp_endpoint,
       browser_tool_attached, gupy_session}}
    Config (--config <json>): {"cdp_url", "user_data_dir",
      "browser_tool": {"tabs": [{"id", "url"}], "active_page": {"url", "authenticated"}}}

Run:
    python3 preflight_test.py
    python3 -m pytest preflight_test.py
"""

import io
import json
import os
import sys
import tempfile
import unittest
import unittest.mock
from contextlib import redirect_stdout
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import preflight  # noqa: E402  (RED phase: module does not exist yet)


CDP_URL = "http://127.0.0.1:9222"
USER_DATA_DIR = "~/.chromium-profile-cdp"
USER_DATA_EXPANDED = os.path.expanduser(USER_DATA_DIR)
GUPY_JOB_URL = "https://jobs.gupy.io/jobs/8472"
GUPY_AUTH_URL = "https://jobs.gupy.io/candidates/auth"

# ---------------------------------------------------------------------------
# Fakes (injected — never any network in this suite)
# ---------------------------------------------------------------------------

CHROMIUM_PROFILE_CMD = (
    "/usr/bin/chromium "
    f"--remote-debugging-port=9222 "
    f"--user-data-dir={USER_DATA_EXPANDED} --no-first-run"
)
CHROMIUM_OTHER_PROFILE_CMD = (
    "/usr/bin/chromium --remote-debugging-port=9223 "
    "--user-data-dir=/home/other/.config/chromium --no-first-run"
)

PAGE_A = {"id": "target-1", "type": "page", "url": GUPY_JOB_URL,
          "title": "Vaga"}
PAGE_B = {"id": "target-2", "type": "page", "url": "chrome://newtab/",
          "title": "Nova aba"}
IFRAME = {"id": "target-3", "type": "iframe", "url": GUPY_JOB_URL,
          "title": "iframe"}  # never counts as a page target


def fake_procs(*command_lines):
    """Return a list_procs fake yielding the given process command lines."""
    return lambda: list(command_lines)


class FakeHttpResponse:
    """Minimal urllib response stand-in: status + read() bytes."""

    def __init__(self, status, payload):
        self.status = status
        self._payload = payload

    def read(self):
        return self._payload


def fake_cdp(targets=None, status=200, exc=None, raw_body=None):
    """Return (state, fetch): a recording CDP HTTP fake.

    Serves the /json target LIST for any path except /json/version (which
    serves a version dict). ``raw_body`` forces a non-JSON 200 body.
    """
    state = {
        "urls": [],
        "targets": targets if targets is not None else [PAGE_A],
        "version": {"Browser": "Chrome/132.0.0.0 (mock CDP)"},
        "status": status,
        "exc": exc,
        "raw_body": raw_body,
    }

    def fetch(url, timeout=None):
        state["urls"].append(url)
        if state["exc"] is not None:
            raise state["exc"]
        if state["raw_body"] is not None:
            return FakeHttpResponse(state["status"], state["raw_body"])
        if url.rstrip("/").endswith("/json/version"):
            payload = json.dumps(state["version"]).encode("utf-8")
        else:
            payload = json.dumps(state["targets"]).encode("utf-8")
        return FakeHttpResponse(state["status"], payload)

    return state, fetch


def write_config(tmp: Path, **overrides) -> Path:
    """Write a preflight config JSON; individual keys may be overridden."""
    config = {
        "cdp_url": CDP_URL,
        "user_data_dir": USER_DATA_DIR,
        "browser_tool": {
            "tabs": [{"id": "target-1", "url": GUPY_JOB_URL}],
            "active_page": {"url": GUPY_JOB_URL, "authenticated": True},
        },
    }
    config.update(overrides)
    path = tmp / "preflight-config.json"
    path.write_text(json.dumps(config), encoding="utf-8")
    return path


def run_cli(config_path):
    """Run preflight.run(['--config', path]) in-process; return (code, data)."""
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = preflight.run(["--config", str(config_path)])
    try:
        data = json.loads(buf.getvalue())
    except json.JSONDecodeError:
        data = {"raw_stdout": buf.getvalue()}
    return code, data


# ---------------------------------------------------------------------------
# Check 1 — chromium_running: process for the DEDICATED profile
# ---------------------------------------------------------------------------

class ChromiumRunningTests(unittest.TestCase):
    """check_chromium_running: a chromium process is running, but only one
    bound to the DEDICATED user-data-dir counts — any chromium is NOT proof."""

    def test_dedicated_profile_running_passes(self):
        result = preflight.check_chromium_running(
            USER_DATA_DIR, list_procs=fake_procs(CHROMIUM_PROFILE_CMD)
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["user_data_dir"], USER_DATA_EXPANDED)
        self.assertEqual(result["process"], CHROMIUM_PROFILE_CMD)

    def test_no_chromium_process_fails(self):
        result = preflight.check_chromium_running(
            USER_DATA_DIR, list_procs=fake_procs()
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CHROMIUM_NOT_RUNNING)
        self.assertTrue(result["detail"])

    def test_chromium_with_different_profile_fails(self):
        # A chromium IS running, but on another profile/instance — the
        # dedicated application profile is not, so the check must fail.
        result = preflight.check_chromium_running(
            USER_DATA_DIR, list_procs=fake_procs(CHROMIUM_OTHER_PROFILE_CMD)
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CHROMIUM_NOT_RUNNING)

    def test_dedicated_profile_missing_among_other_processes_fails(self):
        # Even with unrelated processes present, absence of the dedicated
        # profile process still fails (no false positive from any chromium).
        result = preflight.check_chromium_running(
            USER_DATA_DIR,
            list_procs=fake_procs(CHROMIUM_OTHER_PROFILE_CMD,
                                  "/usr/bin/python3 -m http.server 8000"),
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CHROMIUM_NOT_RUNNING)

    def test_dedicated_profile_found_among_many_processes_passes(self):
        result = preflight.check_chromium_running(
            USER_DATA_DIR,
            list_procs=fake_procs("/usr/bin/python3 -m http.server 8000",
                                  CHROMIUM_PROFILE_CMD),
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["process"], CHROMIUM_PROFILE_CMD)

    def test_tilde_form_user_data_dir_argv_matches_expanded_profile(self):
        # Regression (issue #72 live run): Chromium may be launched with the
        # literal tilde form --user-data-dir=~/.chromium-profile-cdp — the raw
        # pgrep argv then holds the TILDE, not the expanded /home/... path, so
        # a raw-substring match of the expanded dir false-negatives even though
        # the dedicated profile IS running. The argv value must be expanded
        # (normalized on BOTH sides) before comparing.
        tilde_cmd = (
            "/usr/bin/chromium --remote-debugging-port=9222 "
            "--user-data-dir=~/.chromium-profile-cdp --no-first-run"
        )
        result = preflight.check_chromium_running(
            USER_DATA_DIR, list_procs=fake_procs(tilde_cmd)
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["user_data_dir"], USER_DATA_EXPANDED)
        self.assertEqual(result["process"], tilde_cmd)

    def test_tilde_form_with_pgrep_pid_prefix_passes(self):
        # pgrep -af output prefixes the line with the numeric pid — the
        # combined pid+executable window must still recognize the browser and
        # the tilde-form user-data-dir must still normalize to the profile.
        pgrep_line = (
            "23456 /usr/bin/chromium --remote-debugging-port=9222 "
            "--user-data-dir=~/.chromium-profile-cdp --no-first-run"
        )
        result = preflight.check_chromium_running(
            USER_DATA_DIR, list_procs=fake_procs(pgrep_line)
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["user_data_dir"], USER_DATA_EXPANDED)


# ---------------------------------------------------------------------------
# Check 2 — cdp_endpoint: the profile-config CDP endpoint answers /json
# ---------------------------------------------------------------------------

class CdpEndpointTests(unittest.TestCase):
    """check_cdp_endpoint: GET <cdp_url>/json must answer with the CDP target
    list. A 200 from a random port is NOT proof — the payload must be CDP."""

    def test_cdp_answers_json_passes(self):
        state, fetch = fake_cdp(targets=[PAGE_A, PAGE_B, IFRAME])
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertTrue(result["ok"])
        self.assertEqual(result["targets"], 3)
        self.assertEqual(result["url"], CDP_URL.rstrip("/") + "/json")

    def test_cdp_endpoint_queries_json_path(self):
        # Spec §3.1 item 2 says the endpoint must answer /json verbatim.
        state, fetch = fake_cdp(targets=[PAGE_A])
        preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertEqual(state["urls"], [CDP_URL.rstrip("/") + "/json"])

    def test_cdp_connection_refused_fails(self):
        state, fetch = fake_cdp(exc=ConnectionRefusedError("refused"))
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CDP_UNREACHABLE)

    def test_cdp_timeout_fails(self):
        state, fetch = fake_cdp(exc=TimeoutError("timed out"))
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CDP_UNREACHABLE)

    def test_cdp_http_error_fails(self):
        state, fetch = fake_cdp(targets=[PAGE_A], status=404)
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CDP_UNREACHABLE)

    def test_cdp_non_json_payload_fails_closed(self):
        # Port open but the body is not JSON at all — a listening port alone
        # is NOT proof of a CDP endpoint.
        state, fetch = fake_cdp(raw_body=b"<html>apache is here</html>")
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CDP_UNREACHABLE)

    def test_cdp_json_targets_not_a_list_fails_closed(self):
        # 200 + JSON but not the /json target LIST shape — fail-closed: the
        # endpoint is not behaving like CDP.
        state, fetch = fake_cdp(targets={"not": "a list"})
        result = preflight.check_cdp_endpoint(CDP_URL, fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_CDP_UNREACHABLE)


# ---------------------------------------------------------------------------
# Check 3 — browser_tool_attached: attached to THAT SAME CDP endpoint
# ---------------------------------------------------------------------------

class BrowserToolAttachedTests(unittest.TestCase):
    """check_browser_tool_attached: the page-target list served by CDP must
    match the tab list of the configured browser tool. A listening port alone
    is NOT proof — the tool may be attached to a DIFFERENT browser endpoint."""

    def test_same_tab_list_attached_passes(self):
        state, fetch = fake_cdp(targets=[PAGE_A, PAGE_B, IFRAME])
        tool_tabs = [{"id": "target-1", "url": GUPY_JOB_URL},
                     {"id": "target-2", "url": "chrome://newtab/"}]
        result = preflight.check_browser_tool_attached(
            CDP_URL, tool_tabs, fetch=fetch
        )
        self.assertTrue(result["ok"])
        # The iframe target is ignored — only page targets are compared.
        self.assertEqual(result["matched"], 2)
        self.assertEqual(result["cdp_page_ids"], {"target-1", "target-2"})
        self.assertEqual(result["tool_tab_ids"], {"target-1", "target-2"})

    def test_port_open_but_tool_detached_fails(self):
        # CDP answers (port is genuinely open) but the tool reports NO tabs:
        # the tool is not attached to this CDP endpoint at all.
        state, fetch = fake_cdp(targets=[PAGE_A, PAGE_B])
        result = preflight.check_browser_tool_attached(CDP_URL, [], fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)
        self.assertIn("port", result["detail"].lower())  # "listening port alone"

    def test_tool_attached_to_different_browser_fails(self):
        # The port is open and the tool has tabs — but they belong to another
        # browser instance: ids do not match, so the tool is NOT attached to
        # the same CDP endpoint.
        state, fetch = fake_cdp(targets=[PAGE_A])
        tool_tabs = [{"id": "other-browser-target-9", "url": GUPY_JOB_URL}]
        result = preflight.check_browser_tool_attached(
            CDP_URL, tool_tabs, fetch=fetch
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)

    def test_partial_overlap_fails(self):
        # Subsets never count: the tab lists must match EXACTLY (same CDP
        # endpoint), so a partial overlap is still a detached tool.
        state, fetch = fake_cdp(targets=[PAGE_A, PAGE_B])
        tool_tabs = [{"id": "target-1", "url": GUPY_JOB_URL}]
        result = preflight.check_browser_tool_attached(
            CDP_URL, tool_tabs, fetch=fetch
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)

    def test_empty_cdp_tab_list_fails_closed(self):
        # Ambiguous (browser with no tabs): fail-closed, never green.
        state, fetch = fake_cdp(targets=[])
        result = preflight.check_browser_tool_attached(CDP_URL, [], fetch=fetch)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)

    def test_missing_browser_tool_fails_closed(self):
        # Spec §3.4 fail-closed: a missing tool stops the run, never proceeds.
        state, fetch = fake_cdp(targets=[PAGE_A])
        result = preflight.check_browser_tool_attached(
            CDP_URL, None, fetch=fetch
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)


# ---------------------------------------------------------------------------
# Check 4 — gupy_session: authenticated marker + no /candidates/auth redirect
# ---------------------------------------------------------------------------

class GupySessionTests(unittest.TestCase):
    """check_gupy_session: valid only when the active page carries the
    authenticated marker AND is not an auth/login redirect page."""

    def test_valid_gupy_session_passes(self):
        result = preflight.check_gupy_session(
            {"url": GUPY_JOB_URL, "authenticated": True}
        )
        self.assertTrue(result["ok"])
        self.assertEqual(result["url"], GUPY_JOB_URL)
        self.assertEqual(result["authenticated"], True)

    def test_company_subdomain_gupy_session_passes(self):
        result = preflight.check_gupy_session(
            {"url": "https://mtpbrasil.gupy.io/jobs/8472",
             "authenticated": True}
        )
        self.assertTrue(result["ok"])

    def test_candidates_auth_redirect_fails(self):
        # Spec §3.1 item 4: a /candidates/auth redirect invalidates the
        # session EVEN when a marker is present (the redirect trumps it).
        result = preflight.check_gupy_session(
            {"url": GUPY_AUTH_URL, "authenticated": True}
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_GUPY_SESSION_INVALID)

    def test_login_url_fails_even_with_marker(self):
        result = preflight.check_gupy_session(
            {"url": "https://jobs.gupy.io/login?return=%2Fjobs%2F8472",
             "authenticated": True}
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_GUPY_SESSION_INVALID)

    def test_missing_authenticated_marker_fails(self):
        result = preflight.check_gupy_session(
            {"url": GUPY_JOB_URL, "authenticated": False}
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_GUPY_SESSION_INVALID)

    def test_non_gupy_host_fails_closed(self):
        # Cannot validate a Gupy session on an unknown host: fail-closed.
        result = preflight.check_gupy_session(
            {"url": "https://www.google.com", "authenticated": True}
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_GUPY_SESSION_INVALID)

    def test_unparseable_url_fails_closed(self):
        result = preflight.check_gupy_session(
            {"url": "not a url at all", "authenticated": True}
        )
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], preflight.ERROR_GUPY_SESSION_INVALID)


# ---------------------------------------------------------------------------
# Orchestration — run(argv): exit codes, order, verbatim failure, error shapes
# ---------------------------------------------------------------------------

def _ok_check(**facts):
    return {"ok": True, **facts}


def _fail_check(code, detail):
    return {"ok": False, "error": code, "detail": detail}


class RunTests(unittest.TestCase):
    """preflight.run(): full pass → exit 0; any failure → exit 1 with the
    failing check reported verbatim, short-circuited in spec order."""

    def _patch_all(self, chromium=_ok_check(), cdp=_ok_check(),
                   tool=_ok_check(), session=_ok_check()):
        """Start all four check patches; return the mocks for assertion.

        Patches live until the test's cleanup runs — callers must NOT use
        ``with`` blocks, only assert on the returned mocks directly.
        """
        patches = (
            unittest.mock.patch(
                "preflight.check_gupy_session", return_value=session),
            unittest.mock.patch(
                "preflight.check_browser_tool_attached", return_value=tool),
            unittest.mock.patch(
                "preflight.check_cdp_endpoint", return_value=cdp),
            unittest.mock.patch(
                "preflight.check_chromium_running", return_value=chromium),
        )
        mocks = [p.start() for p in patches]
        self.addCleanup(lambda: [p.stop() for p in patches])
        return mocks[0], mocks[1], mocks[2], mocks[3]

    def test_run_full_pass_exits_zero(self):
        m_session, m_tool, m_cdp, m_chromium = self._patch_all()
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 0)
        self.assertTrue(data["ok"])
        self.assertEqual(
            list(data["checks"].keys()),
            ["chromium_running", "cdp_endpoint",
             "browser_tool_attached", "gupy_session"],
        )

    def test_run_forwards_check_facts_verbatim(self):
        chromium = _ok_check(process=CHROMIUM_PROFILE_CMD)
        cdp = _ok_check(targets=2)
        tool = _ok_check(matched=2)
        session = _ok_check(authenticated=True, url=GUPY_JOB_URL)
        m_session, m_tool, m_cdp, m_chromium = self._patch_all(
            chromium=chromium, cdp=cdp, tool=tool, session=session)
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 0)
        self.assertEqual(data["checks"]["chromium_running"], chromium)
        self.assertEqual(data["checks"]["cdp_endpoint"], cdp)
        self.assertEqual(data["checks"]["browser_tool_attached"], tool)
        self.assertEqual(data["checks"]["gupy_session"], session)

    def test_run_stops_at_first_failing_check(self):
        # chromium fails → the later checks must NOT even be evaluated.
        chromium = _fail_check(preflight.ERROR_CHROMIUM_NOT_RUNNING,
                               "no chromium for profile")
        m_session, m_tool, m_cdp, m_chromium = self._patch_all(
            chromium=chromium)
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 1)
        self.assertFalse(data["ok"])
        self.assertEqual(data["check"], "chromium_running")
        self.assertEqual(data["error"], preflight.ERROR_CHROMIUM_NOT_RUNNING)
        self.assertEqual(data["detail"], "no chromium for profile")
        self.assertEqual(set(data["checks"].keys()), {"chromium_running"})
        m_cdp.assert_not_called()
        m_tool.assert_not_called()
        m_session.assert_not_called()

    def test_run_cdp_fail_exits_one_with_shape(self):
        cdp = _fail_check(preflight.ERROR_CDP_UNREACHABLE,
                          "GET http://127.0.0.1:9222/json refused")
        m_session, m_tool, m_cdp, m_chromium = self._patch_all(cdp=cdp)
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 1)
        self.assertEqual(data["check"], "cdp_endpoint")
        self.assertEqual(data["error"], preflight.ERROR_CDP_UNREACHABLE)
        self.assertEqual(data["detail"], "GET http://127.0.0.1:9222/json refused")
        self.assertEqual(set(data["checks"].keys()),
                         {"chromium_running", "cdp_endpoint"})
        m_tool.assert_not_called()
        m_session.assert_not_called()

    def test_run_browser_tool_detached_exits_one(self):
        tool = _fail_check(preflight.ERROR_BROWSER_TOOL_DETACHED,
                           "tool tabs do not match CDP targets (port alone "
                           "is not proof)")
        m_session, m_tool, m_cdp, m_chromium = self._patch_all(tool=tool)
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 1)
        self.assertEqual(data["check"], "browser_tool_attached")
        self.assertEqual(data["error"], preflight.ERROR_BROWSER_TOOL_DETACHED)
        self.assertEqual(set(data["checks"].keys()),
                         {"chromium_running", "cdp_endpoint",
                          "browser_tool_attached"})
        # A detached tool stops the run BEFORE the session is ever probed.
        m_session.assert_not_called()

    def test_run_auth_redirect_exits_one_verbatim(self):
        session = _fail_check(preflight.ERROR_GUPY_SESSION_INVALID,
                              "redirect to /candidates/auth; session expired")
        m_session, m_tool, m_cdp, m_chromium = self._patch_all(session=session)
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            code, data = run_cli(config_path)
        self.assertEqual(code, 1)
        # The failing check is reported VERBATIM (no improvisation/paraphrase).
        self.assertEqual(data["check"], "gupy_session")
        self.assertEqual(data["error"], preflight.ERROR_GUPY_SESSION_INVALID)
        self.assertEqual(data["detail"],
                         "redirect to /candidates/auth; session expired")
        self.assertEqual(
            data["checks"]["gupy_session"],
            {"ok": False, "error": preflight.ERROR_GUPY_SESSION_INVALID,
             "detail": "redirect to /candidates/auth; session expired"},
        )

    def test_run_forwards_config_to_checks(self):
        m_session, m_tool, m_cdp, m_chromium = self._patch_all()
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(
                Path(td),
                cdp_url="http://10.0.0.1:9333",
                user_data_dir="/tmp/dedicated-profile",
                browser_tool={
                    "tabs": [{"id": "target-1", "url": GUPY_JOB_URL},
                             {"id": "target-9", "url": GUPY_AUTH_URL}],
                    "active_page": {"url": GUPY_JOB_URL,
                                    "authenticated": True},
                },
            )
            code, data = run_cli(config_path)
        self.assertEqual(code, 0)
        m_chromium.assert_called_once_with(
            user_data_dir="/tmp/dedicated-profile")
        m_cdp.assert_called_once_with(cdp_url="http://10.0.0.1:9333")
        m_tool.assert_called_once_with(
            cdp_url="http://10.0.0.1:9333",
            tool_tabs=[{"id": "target-1", "url": GUPY_JOB_URL},
                       {"id": "target-9", "url": GUPY_AUTH_URL}])
        m_session.assert_called_once_with(
            page_state={"url": GUPY_JOB_URL, "authenticated": True})

    def test_run_config_defaults_when_omitted(self):
        m_session, m_tool, m_cdp, m_chromium = self._patch_all()
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(
                Path(td),
                cdp_url=None,  # drop the key entirely
                user_data_dir=None,
            )
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config.pop("cdp_url", None)
            config.pop("user_data_dir", None)
            config_path.write_text(json.dumps(config), encoding="utf-8")
            code, data = run_cli(config_path)
        self.assertEqual(code, 0)
        m_chromium.assert_called_once_with(
            user_data_dir=preflight.DEFAULT_USER_DATA_DIR)
        m_cdp.assert_called_once_with(cdp_url=preflight.DEFAULT_CDP_URL)

    def test_run_missing_config_flag_usage_exit_two(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = preflight.run([])
        data = json.loads(buf.getvalue())
        self.assertEqual(code, 2)
        self.assertEqual(data["error"], "usage")
        self.assertTrue(data["detail"])

    def test_run_invalid_config_json_exit_two(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "bad.json"
            path.write_text("not json {", encoding="utf-8")
            code, data = run_cli(path)
        self.assertEqual(code, 2)
        self.assertEqual(data["error"], preflight.ERROR_CONFIG_INVALID)
        self.assertTrue(data["detail"])

    def test_run_config_file_not_found_exit_two(self):
        with tempfile.TemporaryDirectory() as td:
            code, data = run_cli(Path(td) / "missing.json")
        self.assertEqual(code, 2)
        self.assertEqual(data["error"], preflight.ERROR_CONFIG_INVALID)

    def test_run_config_missing_browser_tool_exit_two(self):
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            config = json.loads(config_path.read_text(encoding="utf-8"))
            del config["browser_tool"]
            config_path.write_text(json.dumps(config), encoding="utf-8")
            code, data = run_cli(config_path)
        self.assertEqual(code, 2)
        self.assertEqual(data["error"], preflight.ERROR_CONFIG_INVALID)

    def test_run_config_missing_active_page_exit_two(self):
        with tempfile.TemporaryDirectory() as td:
            config_path = write_config(Path(td))
            config = json.loads(config_path.read_text(encoding="utf-8"))
            del config["browser_tool"]["active_page"]
            config_path.write_text(json.dumps(config), encoding="utf-8")
            code, data = run_cli(config_path)
        self.assertEqual(code, 2)
        self.assertEqual(data["error"], preflight.ERROR_CONFIG_INVALID)


class DefaultConfigConstantsTests(unittest.TestCase):
    """The GREEN module exposes the documented defaults verbatim."""

    def test_default_cdp_url(self):
        self.assertEqual(preflight.DEFAULT_CDP_URL, "http://localhost:9222")

    def test_default_user_data_dir(self):
        self.assertEqual(preflight.DEFAULT_USER_DATA_DIR,
                         "~/.chromium-profile-cdp")


if __name__ == "__main__":
    unittest.main()