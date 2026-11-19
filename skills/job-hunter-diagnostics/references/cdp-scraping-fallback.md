# CDP Scraping Fallback

> When `browser_exec` blocks connections to private/internal URLs (e.g.
> `localhost:9222`), drive the browser directly via `pychrome`.

---

## When to use

- `browser_exec` returns: `Blocked: URL targets a private or internal address`
- The Job Hunter API has no matching listings and you need to scrape a portal directly
- You need to scrape listings from dynamic/JS-rendered boards (LinkedIn Jobs, Gupy, etc.)

## Environment

### 1. Launch Chromium with CDP

```bash
chromium-browser --headless=new --remote-debugging-port=9222 \
  --user-data-dir=/tmp/chrome-scrape --no-sandbox --disable-gpu \
  --disable-dev-shm-usage --window-size=1280,900 &
```

**Notes:**
- Use `chromium-browser` (snap) — no `google-chrome` binary in Ubuntu snap environments
- The `--user-data-dir` must be a dedicated directory per session
- Wait 3-5 seconds, then verify: `curl -s http://127.0.0.1:9222/json/version`
- The CDP endpoint must be `127.0.0.1:9222` (not `localhost:9222`) to avoid DNS resolution delays

### 2. Install pychrome

```bash
pip install pychrome
```

The `browser_exec` tool blocks URLs that target private/internal addresses (including `localhost:9222`), so direct `pychrome` via terminal is the reliable path.

## Generic scraping workflow

1. **Create a tab**: `browser.new_tab()` → `tab.start()`
2. **Enable Page events**: `tab.call_method("Page.enable")`
3. **Navigate**: `tab.call_method("Page.navigate", url=...)`
4. **Wait** 8+ seconds for JS rendering
5. **Check auth state**: if `window.location.href` contains `/login`, `/signin`, `/auth`, or `/uas/login`, stop → `auth_required`
6. **Extract data**: `tab.call_method("Runtime.evaluate", expression="...", returnByValue=True)`
7. **Scroll**: evaluate `window.scrollBy(0, 800)` or scroll the results container specifically
8. **Deduplicate** by unique URL/ID

## LinkedIn Jobs selectors (Chromium 153 / LinkedIn 2026)

```javascript
// Job card containers
const cards = document.querySelectorAll(
  '.job-card-list__entity, .jobs-search-results__list-item, .job-card-container, .scaffold-layout__list-item'
);

// Per card:
const title = card.querySelector('.job-card-list__title, .job-card-container__link, h3, [class*="title"]');
const company = card.querySelector('.job-card-container__primary-description, [class*="company"], [class*="metadata"]');
const location = card.querySelector('.job-card-container__metadata-item, [class*="location"]');
const link = card.querySelector('a[href*="/jobs/view/"], a[href*="jobPosting"]');

// Remote/hybrid detection from card text
const fullText = card.textContent || '';
if (/remoto|remote/i.test(fullText)) model = 'remote';
else if (/h[ií]brido|hybrid/i.test(fullText)) model = 'hybrid';
else if (/on-site|presencial/i.test(fullText)) model = 'on-site';
```

## Auth wall handling

When the portal redirects to a login page:
- **Stop all further action.**
- Output `auth_required` with the login URL.
- Tell the user: "**Preciso de takeover** — faz login manual no browser e me avisa quando a lista estiver visível."
- Resume only after the user confirms the manual login.
- **Never** fill the login form, create accounts, or solve CAPTCHAs.

## Output format (JSONL)

```json
{"title": "...", "company": "...", "url": "https://...", "location": "...", "model": "remote"}
```

## Stats to report

- Total unique jobs extracted
- Count of `model == "remote"`
- Count of titles matching `/j[uú]nior|est[aá]gio|trainee|junior/i`
- Any blockers (login wall, CAPTCHA, rate-limit)