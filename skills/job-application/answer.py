#!/usr/bin/env python3
"""
answer.py — the per-question review gate (docs/specs/answer-policy.md, issue #73).

classify.py classifies PAGES (form | triagem | review | ...); this module owns
the QUESTION-level verdict. The executor plugs it into the #72 runbook at the
REVIEW phase, before any submit action: every question resolved along the fill
phases is run through review_gate() and submit is only allowed when no question
resolves to ASK.

Taxonomy (exactly one verdict per question):
    ANSWER — grounded: a profile field (profile.<field>) or a stored memory
             answer (memory, incl. previously human-dictated answers).
    ASK    — no source; the human must dictate / complete manually. BLOCKS
             submit. The blocker carries a CANONICAL reason code:
                 MANUAL            open text / cover-letter-like / required
                                   consent — human dictates or completes
                 DADOS_PESSOAIS     required personal data (CPF, RG, address,
                                   birth date, PCD, ...) without profile source
                 ELIGIBILITY_BLOCK required eligibility/screening question
                                   without source (or a can't-apply answer)
                 DOUBT             unrecognized/ambiguous question (no label)
    SKIP   — optional consent (leave default/unchecked) OR not applicable
             (ineligible / question does not apply) — never blocks.

Design: marker-based and deterministic — no LLM, no re-deciding. The resolve
order is strict: profile → memory → human_answers → not_applicable → optional
→ ASK. Persistence lives in <memory-dir>/answers/<job_id>.json (the only
extra record tree besides applications/ attempts/ screenshots/); save_answers
overwrites per job (idempotent key = job_id), load_answers returns {} on a
missing/corrupt file so a broken answer memory never crashes the loop.

verdict.py remains the sole writer of attempted/applied records: a submit
blocked at the gate ends as verdict.decide(outcome=INCOMPLETE,
reason=answer.canonical_block_reason(blockers)).
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import verdict  # P0-2 (PR #80): record_blocked_submit routes through verdict.decide

# ---------------------------------------------------------------------------
# Constants — the verdicts, the kinds and the CANONICAL attempt codes
# ---------------------------------------------------------------------------

ANSWER = "ANSWER"
ASK = "ASK"
SKIP = "SKIP"

KIND_CONSENT = "consent"
KIND_PERSONAL_DATA = "personal_data"
KIND_ELIGIBILITY = "eligibility"
KIND_OPEN_TEXT = "open_text"
KIND_PLAIN = "plain"
KIND_UNKNOWN = "unknown"

# Canonical attempt reason codes (answer-policy.md §3.4) — the EXACT strings,
# single source of truth. A blocked submit ends as an attempt record with
# reason = one of these (or a "+"-joined set via canonical_block_reason).
BLOCK_REASONS = frozenset({
    "MANUAL", "DADOS_PESSOAIS", "ELIGIBILITY_BLOCK", "DOUBT",
})

ANSWERS_SUBDIR = "answers"

# ---------------------------------------------------------------------------
# Label markers (PT-BR + EN; lowercased, accents kept and mirrored)
# ---------------------------------------------------------------------------

# consent first: any consent-sounding phrasing short-circuits screening logic.
_CONSENT_MARKERS = (
    "aceito receber", "aceito receba", "desejo receber", "autorizo",
    "concordo em receber", "concordo em ser", "receber ofertas",
    "receber oportunidades", "receber novidades", "receber comunica",
    "newsletter", "li e aceito", "a aceitar os termos", "aceito os termos",
    "aceitei os termos", "termos de uso",
)

# personal data before eligibility (a PCD/screening question still IS personal
# data the profile must source).
_PERSONAL_DATA_MARKERS = (
    "cpf", "rg", "endereço", "endereco", "cep", "data de nascimento",
    "data nascimento", "nascimento", "gênero", "genero", "sexo",
    "estado civil", "deficiência", "deficiencia", "pcd",
)

# open text before eligibility: an open "conte sobre" phrasing mentioning an
# eligibility word is a human-typed answer, not a screening gate.
_OPEN_TEXT_MARKERS = (
    "por que", "por quê", "porque você", "conte", "conta pra gente",
    "descreva", "fale sobre", "explique", "motivo", "why do you", "why are you",
    "tell us", "describe", "about yourself",
)

# eligibility / screening gates (radio/checkbox screening questions).
_ELIGIBILITY_MARKERS = (
    "elegível", "elegivel", "direito de trabalhar", "direito ao trabalho",
    "vínculo empregatício", "vinculo empregaticio", "vínculo", "vinculo",
    "disponibilidade", "disponível para", "disponivel para", "experiência",
    "experiencia", "você possui", "voce possui", "você tem", "voce tem",
    "possui experiência", "possui experiencia", "escolaridade",
    "jornada de trabalho", "turno de trabalho",
)

# question key -> profile field (extract questions carry "q_<label>" keys, so
# both the raw key and the q_-stripped form are resolved).
_KEY_TO_FIELD = {
    "name": "name", "nome": "name", "nome_completo": "name",
    "full_name": "name", "fullname": "name",
    "email": "email", "e-mail": "email",
    "phone": "phone", "telefone": "phone", "celular": "phone", "mobile": "phone",
    "cv": "cv_path", "curriculo": "cv_path", "currículo": "cv_path",
    "curriculum": "cv_path", "resume": "cv_path",
    "cover_letter": "cover_text", "carta": "cover_text",
    "carta_de_apresentacao": "cover_text", "apresentacao": "cover_text",
}

# distinctive label phrasings -> profile field (defensive label fallback).
_LABEL_MARKERS = {
    "name": ("nome completo", "full name"),
    "email": ("e-mail", "correio eletrônico"),
    "phone": ("telefone", "celular", "phone", "mobile"),
    "cv_path": ("currículo", "curriculo", "curriculum", "anexar currículo",
                "anexar curriculo", "resume", "curriculum vitae"),
    "cover_text": ("carta de apresentação", "carta de apresentacao",
                   "cover letter"),
}

# kind -> canonical BLOCK_REASONS code for an ASK question.
_KIND_BLOCK_CODES = {
    KIND_CONSENT: "MANUAL",
    KIND_PERSONAL_DATA: "DADOS_PESSOAIS",
    KIND_ELIGIBILITY: "ELIGIBILITY_BLOCK",
    KIND_OPEN_TEXT: "MANUAL",
    KIND_PLAIN: "MANUAL",
    KIND_UNKNOWN: "DOUBT",
}

# editable AX roles accepted by extract_questions.
_INPUT_ROLES = frozenset({
    "textbox", "textarea", "combobox", "searchbox",
    "checkbox", "radio", "switch", "fileinput", "file",
})
# text-like roles default to required (conservative: never silently skip).
_TEXT_ROLES = frozenset({
    "textbox", "textarea", "combobox", "searchbox",
    "fileinput", "file",
})
_OPTIONAL_MARKERS = ("opcional", "optional")
_REQUIRED_MARKERS = ("*", "obrigatório", "obrigatorio", "required")


# ---------------------------------------------------------------------------
# Text helpers
# ---------------------------------------------------------------------------

def _normalize(text: Any) -> str:
    """Lowercase, strip and collapse whitespace (keeps PT accents)."""
    if not isinstance(text, str):
        return ""
    return " ".join(text.lower().split())


def _strip_text(value: Any) -> Any:
    """Strip string values; a value that collapses to blank is treated as absent.

    PR #80 review P1 — memory-reuse and human dictation normalize their values
    this way: whitespace padding is never persisted as-is, and a blank-after-
    strip value is semantically empty (never reused, never stored).
    """
    if isinstance(value, str):
        value = value.strip()
        if not value:
            return None
    return value


def _has_word(label: str, word: str) -> bool:
    """True when ``word`` is a whole space token (never a substring)."""
    for token in label.split():
        if token.strip(".,!?;:*()[]\"'") == word:
            return True
    return False


# ---------------------------------------------------------------------------
# Public pure classification + resolution
# ---------------------------------------------------------------------------

def classify_question(question: Dict[str, Any]) -> str:
    """Bucket a question into a kind. Fixed priority, marker-based.

    Order: consent → personal_data → open_text → eligibility → plain; an empty
    label is ``unknown`` (the human must review it → DOUBT).
    """
    label = _normalize(question.get("label") or "")
    if not label:
        return KIND_UNKNOWN
    if question.get("consent") or any(m in label for m in _CONSENT_MARKERS):
        return KIND_CONSENT
    if any(m in label for m in _PERSONAL_DATA_MARKERS):
        return KIND_PERSONAL_DATA
    if any(m in label for m in _OPEN_TEXT_MARKERS):
        return KIND_OPEN_TEXT
    if any(m in label for m in _ELIGIBILITY_MARKERS):
        return KIND_ELIGIBILITY
    return KIND_PLAIN


def resolve_profile_source(
    question: Dict[str, Any], profile: Dict[str, Any]
) -> Optional[Tuple[str, Any]]:
    """Return ``(profile field, value)`` when key/label maps to a filled field.

    Key mapping first (exact key, then ``q_``-stripped), then distinctive
    label phrasings; the name field additionally matches the word ``nome``
    (never a substring, so ``Sobrenome`` does not resolve). Returns None when
    no profile field grounds the question. A CONSENT question never resolves
    from a profile source — consent is not profile data, so a label like
    "Aceito receber ofertas por e-mail" must not match the ``e-mail`` field.
    """
    if classify_question(question) == KIND_CONSENT:
        return None
    key = _normalize(question.get("key") or "")
    candidates = [key]
    if key.startswith("q_"):
        candidates.append(key[2:])
    for candidate in candidates:
        field = _KEY_TO_FIELD.get(candidate)
        if field is not None and _filled(profile, field):
            return field, profile[field]

    label = _normalize(question.get("label") or "")
    if label and (_has_word(label, "nome") or _has_word(label, "name")
                  or any(m in label for m in _LABEL_MARKERS["name"])):
        if _filled(profile, "name"):
            return "name", profile["name"]
    for field, markers in _LABEL_MARKERS.items():
        if field == "name" or not label:
            continue
        if any(m in label for m in markers) and _filled(profile, field):
            return field, profile[field]
    return None


def _filled(profile: Dict[str, Any], field: str) -> bool:
    """True when the profile field has a non-blank value."""
    value = profile.get(field)
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    return True


def resolve_question(
    question: Dict[str, Any],
    profile: Dict[str, Any],
    memory_entries: Optional[Dict[str, Dict[str, Any]]] = None,
    human_answers: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Resolve ONE question to its verdict (ANSWER / ASK / SKIP).

    Strict order (spec §3.3) — first match wins, deterministic:
      1. profile source            → ANSWER profile.<field>
      2. stored memory answer      → ANSWER memory (reuse)
      3. dictated human answer     → ANSWER human (persisted afterwards)
      4. not applicable            → SKIP not_applicable
      5. not required              → SKIP optional
      6. no source                 → ASK (reason_code = canonical)
    """
    key = str(question.get("key") or "").strip()
    label = _normalize(question.get("label") or "")

    result: Dict[str, Any] = {
        "key": key,
        "label": label,
        "kind": classify_question(question),
        "field_type": str(question.get("field_type") or ""),
        "verdict": None,
        "source": None,
        "value": None,
        "reason": None,
        "reason_code": None,
    }

    source = resolve_profile_source(question, profile)
    if source is not None:
        result.update(verdict=ANSWER, source=f"profile.{source[0]}",
                      value=source[1])
        return result

    if isinstance(memory_entries, dict) and key in memory_entries:
        entry = memory_entries[key]
        if isinstance(entry, dict) and entry.get("verdict") == ANSWER:
            value = _strip_text(entry.get("value"))
            if value is not None:
                result.update(verdict=ANSWER, source="memory", value=value)
                return result

    if isinstance(human_answers, dict) and key in human_answers:
        value = _strip_text(human_answers[key])
        if value is not None:
            result.update(verdict=ANSWER, source="human", value=value)
            return result

    if question.get("applicable") is False:
        result.update(verdict=SKIP, reason="not_applicable")
        return result

    if not question.get("required", True):
        result.update(verdict=SKIP, reason="optional")
        return result

    result.update(verdict=ASK, reason="no_source",
                  reason_code=blocker_reason(question))
    return result


def blocker_reason(question: Dict[str, Any]) -> str:
    """Canonical attempt code for an ASK question (spec §3.4)."""
    return _KIND_BLOCK_CODES[classify_question(question)]


def review_gate(
    questions: List[Dict[str, Any]],
    profile: Dict[str, Any],
    memory_entries: Optional[Dict[str, Dict[str, Any]]] = None,
    human_answers: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """The review gate — submit is allowed ONLY when no question resolves ASK.

    Returns ``{"submittable", "questions", "blockers", "detail"}``. ``blockers``
    are the ASK entries (each carries the canonical ``reason_code``); the
    executor must surface them to the human and, absent dictation, end the run
    INCOMPLETE with ``canonical_block_reason(blockers)`` as the attempt reason.
    """
    resolved = [resolve_question(q, profile, memory_entries, human_answers)
                for q in questions]
    blockers = [r for r in resolved if r["verdict"] == ASK]
    submittable = not blockers
    return {
        "submittable": submittable,
        "questions": resolved,
        "blockers": blockers,
        "detail": ("all required answers grounded" if submittable
                   else f"{len(blockers)} required question(s) need human input"),
    }


def blocker_codes(blockers: List[Dict[str, Any]]) -> List[str]:
    """Distinct canonical codes in first-seen order (attempt reason input)."""
    codes: List[str] = []
    for blocker in blockers:
        code = blocker.get("reason_code")
        if code and code not in codes:
            codes.append(code)
    return codes


def canonical_block_reason(blockers: List[Dict[str, Any]]) -> str:
    """Single attempt-reason string for a blocked submit ("+"-joined)."""
    return "+".join(blocker_codes(blockers))


def record_blocked_submit(
    memory_dir, *,
    job_id: str,
    attempt_id: str,
    job_url: str,
    portal: str,
    blockers: List[Dict[str, Any]],
    trace: Optional[List[Dict[str, Any]]] = None,
    final_page: Optional[Dict[str, Any]] = None,
    screenshot_path: Optional[str] = None,
    manual_url: Optional[str] = None,
    started_at: Optional[str] = None,
    ended_at: Optional[str] = None,
) -> Dict[str, Any]:
    """Persist a blocked submit as an INCOMPLETE attempt (PR #80 review P0-2).

    The review gate found ASK blockers, so the submit is refused; this writes
    the refusal through the SINGLE record path — ``verdict.decide`` with
    ``outcome=INCOMPLETE`` and the canonical block reason (the "+"-joined
    ``reason_code``s of the blockers). It never writes an applied record: a
    blocked submit must never look applied to idempotency (#27).

    Returns the ``verdict.decide`` routing summary (``outcome``, ``reason``,
    ``written_applied``, ``written_attempt``, ...).
    """
    return verdict.decide(
        memory_dir,
        outcome=verdict.INCOMPLETE,
        reason=canonical_block_reason(blockers),
        job_id=job_id,
        attempt_id=attempt_id,
        job_url=job_url,
        portal=portal,
        started_at=started_at,
        ended_at=ended_at,
        trace=trace,
        final_page=final_page,
        screenshot_path=screenshot_path,
        manual_url=manual_url,
    )


# ---------------------------------------------------------------------------
# AX snapshot → questions (deterministic, marker-based)
# ---------------------------------------------------------------------------

def _key_from_label(label: str, seen: set) -> str:
    """Stable question key from the normalized label, deduplicated.

    Punctation marks (``* : ? ( ) ! . , ; & | / \\``) become spaces so the
    same label yields the same key across runs; duplicates get ``_2``, ``_3``…
    """
    norm = " ".join(label.lower().split())
    for ch in "*:?()!.,;&|/\\":
        norm = norm.replace(ch, " ")
    norm = " ".join(norm.split())
    candidate = f"q_{norm}"
    suffix = 2
    while candidate in seen:
        candidate = f"q_{norm}_{suffix}"
        suffix += 1
    seen.add(candidate)
    return candidate


def extract_questions(
    ax_nodes: Optional[List[Dict[str, Any]]]
) -> List[Dict[str, Any]]:
    """Turn editable AX control nodes into question dicts for the review gate.

    ``required``: text-like roles default True (conservative) unless an
    ``opcional``/``optional`` marker is present; choice controls rely on
    ``*``/``obrigatório``/``required`` markers. ``consent`` is inferred for
    checkbox/switch nodes whose label sounds like consent. ``applicable`` is
    True by default (the executor flips it when a conditional question does
    not apply to this candidate).
    """
    result: List[Dict[str, Any]] = []
    seen: set = set()
    for node in ax_nodes or []:
        if not isinstance(node, dict):
            continue
        role = str(node.get("role") or "").lower().strip()
        if role not in _INPUT_ROLES:
            continue
        label = _node_label(node)
        if not label:
            continue
        lowered = label.lower()
        if role in _TEXT_ROLES:
            required = not any(m in lowered for m in _OPTIONAL_MARKERS)
        else:
            required = any(m in lowered for m in _REQUIRED_MARKERS)
        consent = role in ("checkbox", "switch") \
            and any(m in lowered for m in _CONSENT_MARKERS)
        result.append({
            "key": _key_from_label(label, seen),
            "label": label,
            "field_type": role,
            "required": required,
            "consent": consent,
            "applicable": True,
        })
    return result


def _node_label(node: Dict[str, Any]) -> str:
    """Accessible name first; the value (placeholder-style) as a fallback."""
    name = str(node.get("name") or "").strip()
    if name:
        return name
    return str(node.get("value") or "").strip()


# ---------------------------------------------------------------------------
# Persistence — <memory-dir>/answers/<job_id>.json (profile memory)
# ---------------------------------------------------------------------------

def answers_dir(memory_dir: Path) -> Path:
    return Path(memory_dir) / ANSWERS_SUBDIR


def save_answers(memory_dir: Path, job_id: str, resolved: List[Dict[str, Any]],
                 attempt_id: Optional[str] = None) -> Dict[str, Any]:
    """Persist resolved entries to <memory-dir>/answers/<job_id>.json.

    Overwrites the per-job file (idempotent key = job_id, mirroring the applied
    record). Every resolved entry is kept for the audit trail; only ANSWER
    entries carry a value and become reusable sources. Returns the full record.
    """
    now = datetime.now(timezone.utc).isoformat()
    entries: Dict[str, Any] = {}
    for entry in resolved:
        if not isinstance(entry, dict) or not entry.get("key"):
            continue
        entries[entry["key"]] = {
            "question_key": entry["key"],
            "label": entry.get("label", ""),
            "kind": entry.get("kind", ""),
            "field_type": entry.get("field_type", ""),
            "verdict": entry.get("verdict"),
            "source": entry.get("source"),
            "value": entry.get("value"),
            "answered_at": now if entry.get("verdict") == ANSWER else None,
        }

    record: Dict[str, Any] = {
        "job_id": job_id,
        "updated_at": now,
        "entries": entries,
    }
    if attempt_id is not None:
        record["attempt_id"] = str(attempt_id)

    out_dir = answers_dir(memory_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{job_id}.json"
    out_path.write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return record


def load_answers(memory_dir: Path, job_id: str) -> Dict[str, Any]:
    """Load the stored answer entries for a job, ``{}`` on missing/corrupt.

    A broken answer memory never crashes the loop — resolution simply falls
    back to profile → human.
    """
    path = answers_dir(memory_dir) / f"{job_id}.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    entries = data.get("entries") if isinstance(data, dict) else None
    if not isinstance(entries, dict):
        return {}
    return entries


if __name__ == "__main__":
    import sys
    sys.exit("answer.py is a library module — run answer_test.py instead.")