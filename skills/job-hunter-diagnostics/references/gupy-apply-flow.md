# Gupy Apply Flow — Real Session Notes

> Learned from applying to Programador Junior @ Benner (score 72) via Gupy.

---

## Actual Gupy apply flow

The Gupy apply flow has **3 distinct pages**, not 2:

1. **Job detail page** (`start`)
   - URL: `https://<company>.gupy.io/job/<base64-slug>`
   - Has "Candidatar-se" button
   - `classify.py` returns `start` with signal `apply_button:Candidatar-se`

2. **Review page** (`review`)
   - URL: `https://<company>.gupy.io/candidates/applications/<id>/steps/<id>/<step>`
   - Heading: "Olá {nome}, vamos continuar sua candidatura?"
   - Text: "Você está se candidatando para a vaga {title} na empresa {company}."
   - Has "Continuar" button
   - `classify.py` may return `start` here too (not `review`)

3. **Form page** (`form`)
   - Actual application fields (name, email, phone, resume, cover letter)
   - `classify.py` returns `form`

---

## URL `...` is display truncation, not actual truncation

Gupy API returns job URLs with base64 slugs like `eyJqb2...sIn0=`. The `...` you see in terminal/Python output is **display truncation**, not a truncated URL. The actual URL is ~116 chars and complete.

```python
# WRONG - this is what the terminal shows
url = "https://vemserbenner.gupy.io/job/eyJqb2...sIn0=?jobBoardSource=gupy_portal"

# RIGHT - this is the actual value (116 chars, no ...)
url = "https://vemserbenner.gupy.io/job/eyJqb2JJZCI6MTIyNjE0MjMsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0=?jobBoardSource=gupy_portal"
```

If you see `...` in a URL from the API, the URL is still complete — your display is just truncating it. Write to a file and read back to verify.

---

## browser_navigate may truncate long URLs

The `browser_navigate` tool has been observed to truncate very long URLs. If a URL contains `...` after navigation, the tool may have truncated it. Use CDP `Target.createTarget` with the full URL as a workaround, or verify the landed URL after navigation.

---

## classify.py behavior

`classify.classify_page()` returns `start` for job detail pages that have a "Candidatar-se" button, not `form`. The `form` classification is for the actual application form. This is expected behavior — `start` means "enter the flow here", not "this is a form to fill".

---

## company-scraper CLI gotchas

The `scraper.py` script takes a **URL as the first positional argument**, not `--name`. If you pass `--name`, the script crashes with `ValueError: unknown url type`.

```bash
# WRONG (per current docs)
python3 scraper.py --name "Gaio DataOS"

# RIGHT (actual CLI)
python3 scraper.py "https://gaiodataos.com/" --company "Gaio DataOS"
```

The script is designed for **official company websites**, not job portals. It rejects URLs from LinkedIn, Gupy, Indeed, etc. with `{"error": "portal_domain_skipped"}`. Use corporate domains only.

---

## Session notes

- **Date:** 2026-09-11
- **Vaga:** Programador Junior @ Benner (score 72, ID 167)
- **Result:** Successfully navigated to review page, but browser_navigate kept truncating the URL
- **Workaround:** CDP `Target.createTarget` with full URL worked
- **Lesson:** Always verify landed URL after browser_navigate; if it contains `...`, the tool truncated it
