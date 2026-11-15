# Live Gate Test — 2026-09-20

Production-validated negative + positive test of the `answer.review_gate`
against a real Gupy form. Nothing submitted.

## Setup

- **Vaga**: Desenvolvedor Júnior — `<company>` — `<company>.gupy.io/jobs/<job-id>`
- **Browser**: local Chromium CDP at `127.0.0.1:9222`, dedicated profile
  `~/.chromium-profile-cdp`, session authenticated (page showed the
  personalized greeting)
- **Form URL**: `https://<company>.gupy.io/candidates/jobs/<job-id>/apply`

## Classify (live)

```python
from classify import classify_page
classification = classify_page(url, ax_nodes_from_snapshot)
# → page: "triagem"
# → signals: ['question_group', 'radio', 'combobox', 'button']
```

## Questions (extracted via browser tool AX snapshot, NOT html.parser)

| Key | Label | Type | Required |
|---|---|---|---|
| `q_alguém que trabalha...` | Alguém que trabalha nesta empresa indicou você para esta vaga? | radio | True |
| `q_você trabalha na empresa <company>` | Você trabalha na empresa `<company>`? | radio | True |
| `q_onde você encontrou essa vaga opcional` | Onde você encontrou essa vaga? (Opcional) | combobox | False |

## Negative test — gate blocks submit

```python
gate = answer.review_gate(questions, profile, memory_entries=None, human_answers=None)
```

Result:
```
submittable: False
detail:      2 required question(s) need human input
blockers (2):
  - [MANUAL] "Alguém que trabalha nesta empresa indicou você para esta vaga?" (kind=plain)
  - [MANUAL] "Você trabalha na empresa `<company>`?" (kind=plain)
canonical_block_reason = 'MANUAL'
```

→ Submit correctly blocked. Executor should stop and ask human for dictation.

## Positive test — human-dictated answers pass

```python
human_answers = {
    "q_alguém que trabalha...": "Não",
    "q_você trabalha na empresa <company>": "Não",
}
gate = answer.review_gate(questions, profile, memory_entries=None, human_answers=human_answers)
```

Result:
```
submittable: True
detail:      all required answers grounded
blockers:    []

✅ "alguém que trabalha..."  → ANSWER (source=human)
✅ "você trabalha..."        → ANSWER (source=human)
✅ "onde você encontrou..."  → SKIP (optional)
```

→ Submit allowed (gate passes), but policy gate (`require_confirmation_before_final_submit`)
still blocks the final apply action without `--confirmed` / `--auto-apply`.

## Conclusion

- The review gate correctly blocks/allows based on question sourcing
- Canonical blocker codes match `answer.BLOCK_REASONS` (`MANUAL`)
- Browser tool's AX snapshot is the correct way to extract questions for gate testing
- CDP divergence (Browserbase vs local Chromium) must be verified first
- No custom HTML parsers or batch scripts — use only `classify.py` and `answer.py`
