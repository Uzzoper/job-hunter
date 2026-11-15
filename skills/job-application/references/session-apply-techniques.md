# Session techniques — Gupy apply loop (learned in production)

> Detailed techniques and workarounds discovered while running the apply loop against
> live Gupy pages. These complement the high-level `mcp-apply-loop.md` spec with the
> gritty runtime details the executor actually needs.

---

## 1. React-controlled form submission (CRITICAL — browser_click fails)

**Problem**: The native `browser_click` tool (Browserbase) frequently does NOT
trigger React's synthetic event handlers on Gupy. The click reports success
but the page never advances — the form stays put, modals don't close, and
the "Salvar e continuar" button appears to do nothing.

**Root cause**: Gupy is a React SPA. React listens for synthetic events on
delegated nodes; a programmatic `.click()` dispatched from a different
execution context (Browserbase's cloud browser) often misses React's internal
event bus. The local Chromium CDP (port 9222) shares the same browser process,
so `Runtime.evaluate` calls run in the same JS context and DO trigger React.

**Workaround — ALWAYS use CDP Runtime.evaluate on the local Chromium target**:

```javascript
// Find the button by text, scroll it into view, click via JS in-page
(function(){
  const b = Array.from(document.querySelectorAll('button'))
    .find(x => x.textContent.includes('Salvar e continuar') && x.getBoundingClientRect().width > 0);
  if (b) { b.scrollIntoView({block:'center'}); b.click(); return 'clicked'; }
  return 'not found';
})()
```

Dispatch this via `browser_cdp` with `method: "Runtime.evaluate"` and the
correct `target_id` (the page target, not the iframe or browser_ui).

**For React-controlled text inputs** (e.g., salary expectation), direct
`.value` assignment fails with "Illegal invocation" because React's controlled
component intercepts the native setter. Use the native `browser_type` tool
instead — it simulates keystrokes and React picks them up:

```
browser_click ref=<input_ref>   // focus the field first
browser_type ref=<input_ref> text="Aberto a negociação..."
```

**For radio/checkbox inputs**, clicking the parent `<label>` element works
more reliably than clicking the `<input>` itself. Identify labels by their
index in the DOM (use browser_snapshot to map them).

**Verification**: After every click, take a fresh snapshot. If the page didn't
advance, the click didn't register — retry via CDP with a different target or
dispatch a native mouse event via `Input.dispatchMouseEvent`.

---

## 2. URL truncation artifact (terminal display, NOT data loss)

**Problem**: Gupy job URLs in the SQLite DB contain a long base64 segment like
`eyJqb2JJZCI6MTIyMTI1OTcsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0=`. When printed
to the terminal (via `print()`, `sqlite3` CLI, or even Python's `repr()`), the
terminal truncates this to `eyJqb2...sIn0=`, producing a broken URL that 404s
or redirects to the portal homepage.

**Root cause**: nothing is wrong with the DB — it's a terminal display artifact.
The full URL is there, just invisible in the terminal output.

**Workaround**: always write the URL to a file, then read the file — never
print it to the terminal:

```bash
# CORRECT — write to a private file, then verify by length (never by
# printing: the terminal display truncates long URLs — the exact artifact
# being avoided). Consume the URL from the file (MCP read_file or pass the
# file to the browser), then delete it.
sqlite3 -noheader data/jobhunter.db "SELECT url FROM jobs WHERE id=<ID>" > /tmp/url_<ID>.txt
chmod 600 /tmp/url_<ID>.txt
wc -c /tmp/url_<ID>.txt
# consume via read_file('/tmp/url_<ID>.txt') — the full URL is there
rm -f /tmp/url_<ID>.txt
```

```python
# CORRECT — Python write to file
import sqlite3, os
conn = sqlite3.connect('data/jobhunter.db')
url = conn.execute('SELECT url FROM jobs WHERE id=<ID>').fetchone()[0]
with open(f'/tmp/url_<ID>.txt', 'w') as f:
    f.write(url)
os.chmod(f'/tmp/url_<ID>.txt', 0o600)
# Then read_file('/tmp/url_<ID>.txt') — the full URL is there
# os.remove(f'/tmp/url_<ID>.txt') after consuming
```

```bash
# WRONG — truncated by terminal, produces broken URL
sqlite3 data/jobhunter.db "SELECT url FROM jobs WHERE id=<ID>"
python3 -c "import sqlite3; print(sqlite3.connect('data/jobhunter.db').execute('SELECT url FROM jobs WHERE id=<ID>').fetchone()[0])"
```

**Never** attempt to "decode" or "fix" the base64 segment — the full segment is
there, just invisible. Use it verbatim from the file.

**Also applies to API responses**: `curl -s ... | python3 -c "print(...)"` truncates.
Always `curl -s ... -o /tmp/file.json` then read the file.

---

## 3. Direct job URL navigation redirects to company listing

**Problem**: Navigating directly to a Gupy job URL (e.g.,
`https://<company>.gupy.io/jobs/<job-id>?jobBoardSource=gupy_public_page`) often
redirects to the company's job listing page instead of the job detail.

**Root cause**: Gupy's SPA routing may redirect if the session doesn't have a
pending application for that job, or if the URL token is expired.

**Workaround**: navigate to the company listing page first, then extract the
real job URL from the listing and click it:

```javascript
// On the company listing page, find the job link
(function(){
  const links = Array.from(document.querySelectorAll('a'));
  const job = links.find(l => l.textContent.includes('Full Stack Pleno') && l.href.includes('jobs/'));
  return job ? job.href : 'not found';
})()
```

Then navigate to that URL and click "Candidatar-se".

---

## 4. Token management — single source of truth

**Rule**: `BOT_SERVICE_API_KEY` in the project's `.env` is the canonical token.
Pass it to `apply.py` via `--api-token` to avoid any ambiguity from stale
`api-token.txt` values.

```bash
source .env
python3 apply.py --job-id <ID> --profile ~/gupy_profile.json \
    --api-token "$BOT_SERVICE_API_KEY" --portal gupy \
    --memory-dir ~/.hermes/profiles/jobhunter-bot/memails
```

---

## 5. Browser profile cleanup

**Problem**: `chromium --user-data-dir=~/.chromium-profile-cdp` aborts with
`Failed to create SingletonLock` when a previous instance didn't shut down cleanly.

**Fix** before each session:

```bash
# Kill ONLY the automation chromium — the substring matches the expanded
# $HOME form AND the literal-tilde argv form (preflight.py documents both),
# and the [c] bracket prevents the pattern from matching itself. Never
# kill all chromium processes: that would take down the user's own browser.
pkill -9 -f "[c]hromium-profile-cdp" 2>/dev/null
sleep 2
rm -f ~/.chromium-profile-cdp/SingletonLock \
      ~/.chromium-profile-cdp/SingletonSocket \
      ~/.chromium-profile-cdp/SingletonCookie
```

Then start fresh:

```bash
chromium --remote-debugging-port=9222 \
    --user-data-dir=~/.chromium-profile-cdp \
    --no-first-run --disable-gpu --disable-software-rasterizer >/tmp/chromium.log 2>&1 &
```

---

## 6. Gupy apply flow — page sequence

The typical flow encountered on Gupy:

| Step | Page | Action |
|---|---|---|
| 1 | Job detail | Click "Candidatar-se" |
| 2 | Continuation ("Olá X, vamos continuar?") | Click "Continuar" |
| 3 | Questions ("Perguntas criadas pela empresa") | Fill radio/checkbox, click "Salvar e continuar" (CDP JS click) |
| 4 | Apresente-se (optional modal) | Click "Finalizar candidatura" (NOT "Personalizar") |
| 5 | Review | Click "Finalizar candidatura" (CDP JS click) |
| 6 | Success ("Candidatura finalizada!") | Screenshot + verdict |

**Always classify the current page before acting — don't assume the sequence.**

---

## 7. Screenshot capture & recording

After verified success:
1. **verdict.py** writes `applications/<job_id>.json` (sole writer)
2. **Backend API** marks SENT: `curl -X POST -H "X-Bot-Token: $TOKEN" /api/jobs/<ID>/applied`

---

## 8. Browser session identity — Browserbase vs CDP local (CRITICAL)

**Rule**: ALWAYS set `browser.use_gateway: false` in Hermes config when using
local Chromium CDP. Otherwise the browser tool connects to Browserbase (cloud)
and misses the authenticated session.

```yaml
browser:
  use_gateway: false
  cdp_url: http://localhost:9222
```

---

## 9. Screening answers — declared values only (from bot memory)

When filling screening questions, use ONLY declared values from bot memory
(`memories/MEMORY.md` — the agent's memory file under the bot profile on the
VM). Real values are never committed to this repository (it is public); the
table documents the format and provenance rule:

| Question | Answer | Source |
|---|---|---|
| *(screening question)* | `<declared value>` | `<memory entry / user correction date>` |

**NEVER invent screening answers.** If a question has no declared source,
ask the human before proceeding.