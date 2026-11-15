# Gupy Application Flow — Session Notes

Documented flow for Gupy job applications, validated in production.

## Standard Application Flow

```
Job page → "Candidatar-se" → "Continuar" (auth gate) → "Dados adicionais"
  → "Salvar e continuar" → "Perguntas criadas pela empresa" → "Responder agora"
  → [triagem: último salário / pretensão / parente] → "Salvar e continuar"
  → "Apresente-se!" → "Personalizar candidatura" → cover letter + até 3 habilidades
  → "Finalizar candidatura" → [result page: success or rejection]
```

Take the "Personalizar candidatura" branch whenever the user asks for
skills/cover letter per job; the plain "Finalizar candidatura" branch skips
personalization entirely.

The "Dados adicionais" step usually arrives with the radios already answered
(Não/Não) — accept the locked answers and save rather than forcing a change.

## Cover Letter (Apresente-se!)

The personalization screen holds a free-text presentation (`#personalization-step-text-area`,
max 1.500 chars) plus a picker of up to 3 skills from the registered resume.

- Write a **short (~350 chars), job-specific** presentation — name, stack
  focus, one concrete deliverable, and a line aimed at that company. Do NOT
  paste the long generic `cover_text` from the profile.
- Keep it **unaccented and gender-neutral** (no "(a)" markers): the form is
  short and clean text reads better.
- Select up to 3 skills that match the posting; the counter must read
  `3 / 3 habilidades selecionadas` and the char counter must match the text
  length before finalizing.
- The textarea is React-controlled: set the value via the native prototype
  setter and dispatch bubbling `input`/`change` events, then re-read it —
  `fill_input` alone can leave the counter at 0.

## Page Types (classify.py)

| Page | URL Pattern | Action |
|---|---|---|
| `start` | `/job/<token>` | Click "Candidatar-se" |
| `auth` | `/candidates/auth` or `/candidates/jobs/<id>/apply` | Click "Continuar" (NOT login — already authenticated) |
| `form` | `/candidates/applications/<app_id>/steps/<step_id>/curriculum` or similar | Fill radios/inputs, click "Salvar e continuar" |
| `triagem` | Screening questions page | Select answers, click "Salvar e continuar" |
| `review` | "Apresente-se!" modal | Click "Finalizar candidatura" (skip personalization) |
| `sucesso` | Post-submit confirmation | Screenshot + verdict |

## Known Interstitials

1. **"Olá [nome], vamos continuar sua candidatura?"** — Auth gate AFTER "Candidatar-se". Click "Continuar". This is NOT an expired session — it's a resume/checkpoint screen.
2. **"Apresente-se!" modal** — Final interstitial before submission. Two buttons: "Personalizar candidatura" (video, optional) and "Finalizar candidatura". Always click "Finalizar candidatura" to complete the application.
3. **Rejection page** — Some applications are rejected after screening questions (eliminatory criteria). Example text: "Algumas das perguntas foram definidas como eliminatória e sua resposta infelizmente não foi compatível". This is NOT a failure — the application WAS submitted and processed. Record as REJECTED, not INCOMPLETE.

## Screening Questions Pattern

- Radios: `input[type="radio"]` inside `<label>` elements. Use `label.click()` or `input.click()` via CDP if native click fails.
- Textareas are React-controlled: set the value through the native prototype
  setter and dispatch bubbling `input`/`change` events, then re-read the field.
- Read the **actual question labels on the page**: the company-question set
  varies per posting. The common trio is último salário / pretensão salarial /
  parente, but a job may ask a single question (e.g. "Informe uma faixa salarial
  que considere atrativa para posição") or a different set entirely. Values
  come from the stored profile / bot memory (pretensão `R$ 3.500,00` and
  "parente: Não" are standing user preferences) — never invent one.
- Every other required question resolves from the profile or memory; an
  ungrounded required answer blocks the submit (see the answer policy in
  SKILL.md).

## Failure Modes

| Symptom | Cause | Fix |
|---|---|---|
| `browser_click` doesn't advance page | React-controlled button ignores synthetic clicks | CDP Runtime.evaluate fallback: `document.querySelector('button').click()` |
| Text input not persisting | React controlled input | Use `browser_type` (dispatches keyboard events) |
| Radio not selecting via CDP | Wrong target or name collision | Find by label text + index, click the label |
| "Campo obrigatório" after save | Input not properly filled | Re-snapshot, re-fill with browser_type, verify value in field |
| Vaga sumiu (NOT_AVAILABLE) | Job expired/removed from Gupy | Mark as NOT_AVAILABLE, continue to next |

## React Input Technique

For React-controlled text inputs, direct `.value =` assignment does NOT work. Use:

```javascript
// Via CDP Runtime.evaluate (last resort for React inputs):
const input = document.querySelector('input[type="text"]');
const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
nativeSetter.call(input, 'new value');
input.dispatchEvent(new Event('input', {bubbles: true}));
input.dispatchEvent(new Event('change', {bubbles: true}));
```

BUT: prefer `browser_type` tool which handles this automatically.

## Preflight Config Lifecycle

`<memory-dir>/preflight-config.json` carries CDP tab ids, and those change every
time Chromium is (re)started. Regenerate it from the live CDP state **before**
a real run:

1. `GET <cdp_url>/json` → the `page` target ids are the `browser_tool.tabs`
   ids. The browser tool drives THIS SAME Chromium instance, so the `/json`
   page ids are literally the tool's tab ids — navigate once with the tool and
   re-read `/json` to see the target change if attachment is ever in doubt.
2. Open `https://portal.gupy.io/my/applications` and confirm the candidate's
   own applications render with no `/candidates/auth` redirect — that observed
   state is the `authenticated: true` marker (never invent it).
3. Point `active_page` at that URL.

It is a setup artifact: refresh it before the run, never edit it mid-run.

## Verification Checklist

- [ ] After "Salvar e continuar", page advanced to next step
- [ ] After "Finalizar candidatura", page shows result (success or rejection text)
- [ ] For rejections: record the eliminatory criteria that caused rejection
- [ ] Screenshot saved at `<memory-dir>/screenshots/<job_id>.png`
