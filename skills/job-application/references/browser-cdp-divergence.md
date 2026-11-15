# Browser CDP Divergence — Local Chromium vs Hermes Browser Tool

## Problem

When a local Chromium CDP is running and authenticated (e.g., port 9222), but the Hermes browser tool is NOT connected to it, the bot encounters a critical divergence:

- **CDP browser**: Shows authenticated session (e.g., `<company>.gupy.io/candidates/applications/<app-id>/steps/...`)
- **Browser tool** (Browserbase/Browser-Use cloud): Redirects to `/login` or `/candidates/auth`

## Root Cause

Hermes v0.20.1 `browser_tool.py:2306-2314`:
```python
cdp_override = _get_cdp_override()
if cdp_override and not force_local:
    session_info = _create_cdp_session(task_id, cdp_override)  # CDP path
elif force_local:
    session_info = _create_local_session(task_id)
else:
    provider = _get_cloud_provider()
    if provider is None:
        session_info = _create_local_session(task_id)
    else:
        session_info = provider.create_session(task_id)  # Cloud path
```

The `browser` config section is read from `~/.hermes/profiles/<profile>/config.yaml`. With:
```yaml
browser:
  cloud_provider: browser-use
  use_gateway: true
  cdp_url: http://localhost:9222
```

The `_get_cdp_override()` returns the URL, BUT if `cloud_provider` is NOT `local`, the cached `_get_cloud_provider()` may return a non-None value from a previous call, bypassing the CDP override.

**Key finding:** On v0.20.1, `use_gateway` is NOT the relevant toggle — `cloud_provider` is. The code at line 822-830 explicitly checks for `cloud_provider == "local"` to disable cloud fallback.

## Detection Protocol

1. After first `browser_navigate` redirects to `/login` or `/candidates/auth`:
   ```bash
   curl -s http://localhost:9222/json | python3 -c "import sys,json; [print(t['url'][:100]) for t in json.load(sys.stdin) if t['type']=='page']"
   ```
2. If CDP shows the same URL authenticated → browser tool is on wrong backend.

## Resolution

**Do NOT:**
- Kill the Chromium process
- Create CDP scripts
- Change `use_gateway` (does not affect routing on v0.20.1)

**Do:**
1. Set `cloud_provider: local` in profile config:
   ```bash
   hermes config set browser.cloud_provider local
   ```
2. `/reset` to clear `_active_sessions` cache
3. Re-run `apply.py --check-browser`

## Reference

- Hermes source: `tools/browser_tool.py` lines 515-551 (`_get_cdp_override_raw`, `_get_cdp_override`), 798-898 (`_resolve_cloud_provider_uncached`), 2306-2314 (session creation routing)
- Config path: `~/.hermes/profiles/<profile>/config.yaml`
