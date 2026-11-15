# CDP React Button Fallback — Production Pattern

When `browser_click` does NOT advance the page after 2 attempts (snapshot unchanged),
the button is likely React-controlled and ignores synthetic DOM clicks. Use CDP
`Runtime.evaluate` with the correct `target_id`.

## Steps

1. **Get the page target_id:**
   ```
   browser_cdp(method="Target.getTargets")
   → find entry with type="page" and the current URL → note its targetId
   ```

2. **Click via CDP:**
   ```
   browser_cdp(
     method="Runtime.evaluate",
     target_id="<page_target_id>",
     params={
       "expression": "(function(){ var btns = document.querySelectorAll('button'); for(var i=0; i<btns.length; i++){ if(btns[i].textContent.trim() === '<BUTTON_LABEL>'){ btns[i].scrollIntoView({block:'center'}); btns[i].click(); return 'clicked'; } } return 'not found'; })()",
       "returnByValue": true
     }
   )
   ```

## Common Gupy Buttons Requiring This

| Button | Page | Notes |
|---|---|---|
| "Continuar" | Auth gate (`/candidates/jobs/<id>/apply`) | Native click often fails |
| "Salvar e continuar" | Form steps / screening | Works sometimes, CDP as fallback |
| "Responder agora" | Screening questions page | Usually needs CDP |
| "Finalizar candidatura" | "Apresente-se!" modal | Always needs CDP |

## Why This Works

- Gupy is a React SPA — native `browser_click` dispatches synthetic events that React
  ignores for certain buttons (especially inside modals and auth gates).
- CDP `Runtime.evaluate` runs JavaScript in the page context, triggering the actual
  React event handlers.
- The `scrollIntoView` ensures the button is visible before clicking.

## Text Inputs Still Use `browser_type`

This fallback is ONLY for button clicks. For text inputs, `browser_type` always
works because it dispatches proper keyboard events that React respects.
