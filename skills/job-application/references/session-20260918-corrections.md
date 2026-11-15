# Session Corrections — 2026-09-18

Workflow corrections and pitfalls discovered during the 2026-09-18 batch
run. These override or extend the existing `job-application` guidance.

---

## 1. URL truncation is DISPLAY-ONLY — never hex-decode

**Signal:** The terminal, `read_file`, and `python3 -c "print(x)"` truncate long
strings with a literal `...` (e.g. `https://<company>.gupy.io/job/eyJqb2...sIn0=...`).
The database stores the URL **intact**.

**Test decisivo (ONE query, then stop):**

```sql
SELECT id, length(url), url LIKE '%...%' AS has_dots FROM jobs WHERE id IN (...);
```

- `has_dots = 0` every time → URL is whole, the `...` was only the terminal
  wrapping. Use the URL verbatim from `curl`/API; do NOT decode, re-encode, or
  truncate further.
- Only if `has_dots = 1` would the DB itself hold a truncated value (has never
  been observed).

**Pitfall:** An entire session was lost to hex-decoding URLs that were never
broken. Do not repeat.

---

## 2. Session expiry is the #1 stall cause

Gupy sessions expire in 5–10 minutes of idle. Symptoms:

- `browser_navigate` lands on `/candidates/auth` instead of the job page
- `browser_snapshot` shows the login form even though Chromium is running
- After filling a form the page redirects to `/login`

**Rule:** When a login/auth page appears mid-flow, **do not fill it**. Stop,
ask the user to confirm they logged in, then re-run from the job page. Never
retry the same stale snapshot — always take a fresh snapshot after login.

---

## 3. SPA navigation: `browser_click` does NOT navigate

Gupy is a React SPA. Clicking a link with `browser_click @eN` often fires the
on-click handler but does NOT push a new history entry or re-render. The snapshot
comes back unchanged.

**Fix:** Use `browser_navigate` to the job's canonical URL directly. For example,
after listing a job on the company page, extract the link `href` via CDP
(`document.querySelector('a').href`) and navigate there, rather than clicking the
link.

For buttons that must be clicked (e.g. `Salvar e continuar`), try native click
first; if the snapshot is unchanged after 2 attempts, fall back to CDP
`Runtime.evaluate` with the pattern in the skill's "CDP Runtime.evaluate
fallback" section.

---

## 4. React text inputs refuse direct assignment

`Runtime.evaluate` with `input.value = '...'` on a React-controlled field
silently fails (React re-renders the empty value on next state update).

**Fix:** Either:
- Use `browser_type @ref "text"` which dispatches real keydown/keyup/input
  events; or
- In CDP, use the native setter:
  ```js
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value').set;
  setter.call(input, 'value here');
  input.dispatchEvent(new Event('input', {bubbles: true}));
  ```

Always re-read the field after setting to verify persistence.

---

## 5. The "Apresente-se!" modal blocks completion

After screening questions, Gupy shows a modal titled "Apresente-se!" with buttons
"Personalizar candidatura" (video intro, optional) and "Finalizar candidatura".

A pending "Finalizar" button is **proof the flow is incomplete**. Click
"Finalizar candidatura" to submit. Do not treat the modal as a success state.

---

## 6. `browser_click` "Unknown ref" = stale snapshot

`browser_click @eN` fails with `Unknown ref: eN` when the page changed since the
last `browser_snapshot` (the ref points to a DOM node that no longer exists).

**Rule:** Always re-snapshot immediately before each click. After any navigation
or form submission, assume refs are invalid until refreshed.

---

## 7. Partial completion leaves the user with emails about unfinished apps

An application is not "done" until the portal shows the success text
("Candidatura finalizada!"). Starting a flow and walking away because the browser
session expired sends the user automated "finalize sua candidatura" emails.

**Rule:** If the flow cannot be completed in one continuous pass (session expiry,
vaga 404, eligibility block), report the status explicitly to the user so they
know whether the application actually went through.

---

## 8. DADOS_PESSOAIS barrier → MANUAL outcome

Some Gupy screening forms ask for personal data the bot cannot legally possess:
RG, órgão/estado emissor, nome da mãe, nome do pai, naturalidade.

**Rule:** When a textbox is labeled with any of the above, **do NOT invent or
ask for the data**. Instead:

1. Stop the flow.
2. Record a MANUAL attempt at `<memory-dir>/attempts/<job_id>.json`:
   ```json
   {
     "job_id": "<id>",
     "job_company": "<company>",
     "job_title": "<title>",
     "outcome": "MANUAL",
     "barrier": "DADOS_PESSOAIS (RG, órgão/estado emissor, nome da mãe, nome do pai, naturalidade)",
     "url": "<gupy job url>",
     "match_score": 67,
     "recorded_at": "<ISO timestamp>"
   }
   ```
3. Continue to the next job.
4. At session end, report a **fila manual** table:
   | ID | Empresa | Score | Link |
   The user will complete these manually.
