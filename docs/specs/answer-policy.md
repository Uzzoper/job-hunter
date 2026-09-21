# Answer Policy (issue #73)

Spec for the per-question review gate. Companion to
`deterministic-apply-runbook.md` (§3.2 plugs the review gate: "all required
answers grounded (see #73 for the answer policy), explicit user confirmation
for optional consents"). Pure-`stdlib` TDD, same RED → GREEN → REFACTOR and
same style as issue #72 (`preflight.py` / `intent.py` / `verdict.py` contract
modules).

## 1. Problem

The apply loop fills question fields from the live AX snapshot. Observed risk:
the executor invents answers for screening questions and cover-letter-like
open text (the `SKILL.md` fill-provenance rule already says "values with no
declared source are asked to the human — never invented", but the *rule* is
prose, not a gate). A submit on the review page must be refused unless every
required answer is GROUNDED: sourced from the profile or from bot memory
(including previously human-dictated answers), or legitimately skipped
(optional consent / not applicable).

## 2. Current state (grounding)

- `classify.py` classifies **pages** (`form | triagem | review | sucesso |
  auth | erro | start`) — it never decides a question's answerability.
- `verdict.py` remains the sole writer of `applications/` and `attempts/`
  records; its attempt contract accepts a free-string `reason`.
- `apply.py` emits a selector-free intent (identity/profile/policy/metadata);
  the executor derives every action per step and could fill any field.
- No module owns the QUESTION-level verdict (ANSWER/ASK/SKIP) or persists
  answered questions for reuse.
- Known naming: issue #73 = answer policy; the runbook §3.2 references it from
  the submit gate.

## 3. Design — `answer.py` (new, stdlib-only, same style as `classify.py`)

### 3.1 Taxonomy per question

Every question resolved by the review gate gets exactly one verdict:

| Verdict | Meaning | Grounded? | Blocks submit? |
|---|---|---|---|
| `ANSWER` | A source exists: profile field (`profile.<field>`) or a stored memory answer (`memory`, incl. previously human-dictated) | yes | no |
| `ASK` | No source — the human must dictate the answer (or complete manually) | no | **yes** |
| `SKIP` | Optional consent (leave default/unchecked) **or** not applicable (ineligible / question does not apply) | n/a | no |

### 3.2 Question shape

The gate operates on question dicts (produced by `extract_questions` from the
AX snapshot, or supplied directly by a caller):

```json
{
  "key": "q_cpf",
  "label": "CPF",
  "field_type": "textbox",
  "required": true,
  "consent": false,
  "applicable": true
}
```

- `key` — stable identity for memory reuse. `extract_questions` derives it
  from the normalized label (`q_<lowercased label>`), deduplicated with
  `_2`, `_3`… — stable across runs for the same stable page label.
- `label` — human-readable question text (or field name).
- `field_type` — AX role of the control (`textbox | textarea | combobox |
  searchbox | checkbox | radio | switch | file…`).
- `required` — `true`/`false`; text inputs default `true` (conservative),
  choice controls rely on label markers (`*`, `obrigatório`, `required`).
  `extract_questions` flips text inputs to optional on `opcional`/`optional`
  markers.
- `consent` — explicit consent flag, or inferred from consent markers.
- `applicable` — `false` when the question does not apply to this candidate
  (ineligible / conditional question whose precondition is unmet) → `SKIP`
  with reason `not_applicable`.

### 3.3 Resolve order (`resolve_question`)

Strict, deterministic, first match wins — no LLM, no re-deciding:

1. **Profile source** — the question key/label maps to a profile field
   (`name`, `email`, `phone`, `cv_path`, `cover_text`); the profile value is
   non-blank → `ANSWER`, `source="profile.<field>"`.
2. **Memory reuse** — a stored answer exists for the question key in
   `<memory-dir>/answers/<job_id>.json` with `verdict: ANSWER` and a value →
   `ANSWER`, `source="memory"`.
3. **Human dictation** — a dictated answer is supplied for the key
   (`human_answers`) → `ANSWER`, `source="human"` (used once, then persisted
   for reuse).
4. **Not applicable** — `applicable is False` → `SKIP`,
   `reason="not_applicable"`.
5. **Optional** — `required is False` → `SKIP`, `reason="optional"`. This is
   where optional consents land.
6. **ASK** — no source, no skip → `ASK`, `reason="no_source"`,
   `reason_code=<canonical>` (below). Required consents also land here: the
   bot cannot self-attest legal consent, the human must authorize —
   `reason_code=MANUAL`.

`classify_question` buckets the question label into a kind (marker-based, in
this fixed priority): `consent` → `personal_data` → `eligibility` →
`open_text` → `plain`; an empty label is `unknown`.

### 3.4 Canonical attempt codes (reason codes)

A submit blocked at the review gate produces an attempt record
(`verdict.decide(outcome=INCOMPLETE, reason=<canonical code>)`). The reason
vocabulary is canonical — these exact strings, no improvisation:

| Code | Trigger |
|---|---|
| `MANUAL` | open-text / cover-letter-like question without a source, or a required consent — the human must dictate or complete manually |
| `DADOS_PESSOAIS` | required personal-data question (CPF, RG, address, birth date, PCD… ) with no profile source |
| `ELIGIBILITY_BLOCK` | required eligibility/screening question (right to work, vínculo, availability, required experience…) with no source, or a verdict that makes the candidate ineligible |
| `DOUBT` | unrecognized/ambiguous question (no label, no key) — human must review |

`answer.BLOCK_REASONS` is the single source of truth
(`frozenset({"MANUAL", "DADOS_PESSOAIS", "ELIGIBILITY_BLOCK", "DOUBT"})`).
Helpers: `blocker_reason(question)` maps a question to its canonical code;
`blocker_codes(blockers)` returns the distinct codes in first-seen order and
`canonical_block_reason(blockers)` joins them with `+` for the attempt reason.

### 3.5 Review gate (`review_gate`)

Called by the executor in the **review phase, before any submit action**
(runbook §3.2 — submit requires *all required answers grounded*):

```
{"submittable": true,  "questions": [...], "blockers": [], "detail": "..."}
{"submittable": false, "questions": [...], "blockers": [<ASK entries>], "detail": "..."}
```

- `submittable` is true IFF no question resolved to `ASK`.
- On a block, the executor must: print the blockers, ask the human, route the
  dictated answers through `resolve_question(..., human_answers=...)`,
  `save_answers`, then re-run the gate. Without human input the run ends
  `INCOMPLETE` with the canonical `reason` (see §3.4).

### 3.6 Persistence — profile memory

New record tree under the bot memory dir (the only record trees are
`applications/`, `attempts/`, `screenshots/`, and now `answers/`):

    <memory-dir>/answers/<job_id>.json

```json
{
  "job_id": "<id-slug>",
  "updated_at": "<ISO timestamp>",
  "attempt_id": "<optional run uuid that produced the answers>",
  "entries": {
    "q_cpf": {
      "question_key": "q_cpf",
      "label": "CPF",
      "kind": "personal_data",
      "field_type": "textbox",
      "verdict": "ANSWER",
      "source": "human",
      "value": "123.456.789-00",
      "answered_at": "<ISO timestamp>"
    }
  }
}
```

- `save_answers(memory_dir, job_id, resolved, attempt_id=None)` persists every
  resolved entry (audit trail) and overwrites the per-job file (idempotent per
  job, mirroring `applications/<job_id>.json`).
- `load_answers(memory_dir, job_id)` returns the `entries` dict, `{}` on
  missing/corrupt file — a broken answer memory never crashes the loop.
- Reuse: entries with `verdict: ANSWER` and a value act as a source on
  subsequent runs (source `memory`). Profile sources always outrank memory.

## 4. Plug into the #72 runbook

```
preflight → intent → navigate → fill → review → submit → record
                                      │
                          review_gate(questions, profile, memory)
                          ├─ submittable  ──► submit (needs confirmation)
                          └─ blockers     ──► ASK human → save_answers →
                                              re-gate; no input → INCOMPLETE
                                              reason = canonical_block_reason
```

The gate is read-only (pure re-resolution + memory load) until `save_answers`
writes the profile-memory answers tree — consistent with §3.3 tool allow-list
(verdict.py remains the sole writer of attempt/applied records; this module
writes ONLY `answers/<job_id>.json`).

## 5. Test plan (TDD, RED → GREEN → REFACTOR)

New `answer_test.py` (plain `unittest`, no Spring, no network; a temp dir as
the memory dir):

- `ReviewTaxonomyTests` — ANSWER from profile; ASK blocks with the exact
  canonical code per kind (open_text → `MANUAL`, personal_data →
  `DADOS_PESSOAIS`, eligibility → `ELIGIBILITY_BLOCK`, unknown → `DOUBT`);
  SKIP for optional consent (`optional`) and for `applicable: false`
  (`not_applicable`); required consent → ASK `MANUAL`.
- `ReviewGateTests` — all-grounded submittable; one ASK blocks submit and
  lists the blocker; skips+answers only → submittable; empty question list →
  submittable.
- `MemoryReuseTests` — human-dictated answer saved then reused as
  `source="memory"` on a fresh resolve; save/load round-trip; profile wins
  over stale memory; corrupt answer file loads `{}`.
- `QuestionExtractionTests` — required textbox (asterisk) → required; choice
  control without marker → optional; `opcional` marker flips a text input to
  optional; consent checkbox inferred; non-input nodes ignored; keys stable
  from labels and deduplicated.
- `CanonicalCodeTests` — `BLOCK_REASONS` equals the exact set; `blocker_codes`
  distinct first-seen order; `canonical_block_reason` joins with `+`.

## 6. Acceptance criteria (#73)

- A required question without a source refuses submit (ASK) — never invented,
  never filled blind.
- Optional consents and not-applicable questions skip without blocking.
- A human-dictated answer is persisted once and reused on the next run
  (source `memory`).
- Blocked attempts carry one of the canonical codes
  (`MANUAL | DADOS_PESSOAIS | ELIGIBILITY_BLOCK | DOUBT`) as their reason.

## 7. Out of scope

- Hermes-side per-phase tool visibility (integration follow-up, runbook §3.3).
- Semantics of any specific answer value (answer correctness is the human's)
  — the gate only proves a grounded source exists.
- Auto-dictation by the LLM — dictation is always an explicit human input.