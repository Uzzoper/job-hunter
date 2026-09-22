#!/usr/bin/env python3
"""
answer_test.py — issue #73 tests for answer.py (the per-question review gate).

Spec: docs/specs/answer-policy.md. answer.py is a NEW stdlib-only module
(same style as classify.py) that owns the QUESTION-level verdict — classify.py
classifies PAGES, never questions. It resolves every question on the apply
flow to exactly one verdict:

    ANSWER — a source exists (profile.<field> or a stored memory answer,
             incl. previously human-dictated) — grounded, never blocks.
    ASK    — no source — the human must dictate / complete manually —
             BLOCKS submit.
    SKIP   — optional consent (leave default/unchecked) OR not applicable
             (ineligible / question does not apply) — never blocks.

The reviewer plugs the gate into the #72 runbook at the REVIEW phase, before
any submit (deterministic-apply-runbook.md §3.2: "submit requires the review
gate: all required answers grounded"). A blocked submit ends as an attempt
record with outcome INCOMPLETE and a CANONICAL reason code
(MANUAL | DADOS_PESSOAIS | ELIGIBILITY_BLOCK | DOUBT) — never a free-form
sentence.

Plain unittest (pytest-compatible), no Spring, no network: a temp dir stands
in for the bot memory dir. The persistence tree is
<memory-dir>/answers/<job_id>.json (the only extra tree besides applications/,
attempts/ and screenshots/).

Contract the GREEN module must implement (documented here so the RED run fails
only on the missing import and nothing else is ambiguous):

  Constants:
    ANSWER = "ANSWER", ASK = "ASK", SKIP = "SKIP"
    KIND_CONSENT / KIND_PERSONAL_DATA / KIND_ELIGIBILITY / KIND_OPEN_TEXT /
    KIND_PLAIN / KIND_UNKNOWN  → "consent" | "personal_data" | "eligibility" |
                                 "open_text" | "plain" | "unknown"
    BLOCK_REASONS = frozenset({"MANUAL", "DADOS_PESSOAIS",
                               "ELIGIBILITY_BLOCK", "DOUBT"})
    ANSWERS_SUBDIR = "answers"

  Functions:
    classify_question(question) -> str kind
       consent → personal_data → open_text → eligibility → plain; empty label
       → "unknown" (fixed priority, marker-based, deterministic).
    resolve_profile_source(question, profile) -> Optional[Tuple[str, Any]]
       (profile field, value) when the question key/label maps to a non-blank
       profile field (name/email/phone/cv_path/cover_text).
    resolve_question(question, profile, memory_entries=None,
                     human_answers=None) -> dict
       {"key","label","kind","field_type","verdict","source","value","reason",
        "reason_code"}
       Order: profile → memory → human_answers → not_applicable → optional →
       ASK(reason_code=canonical).
    review_gate(questions, profile, memory_entries=None, human_answers=None)
       -> {"submittable", "questions", "blockers", "detail"}
    extract_questions(ax_nodes) -> list of question dicts
    blocker_reason(question) -> str  (canonical code for an ASK question)
    blocker_codes(blockers) -> list of distinct codes in first-seen order
    canonical_block_reason(blockers) -> str ("+"-joined distinct codes)
    save_answers(memory_dir, job_id, resolved, attempt_id=None) -> dict
    load_answers(memory_dir, job_id) -> dict of entries ({} when missing/corrupt)

Run:
    python3 answer_test.py
    python3 -m pytest answer_test.py
"""

import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

# Allow direct import when running from the skill dir or the repo root.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import answer  # noqa: E402  (RED phase: module does not exist yet)
import verdict  # noqa: E402  (P0-2: record_blocked_submit routes through verdict.decide)


JOB_ID = "dev-backend-jr-8472"
ATTEMPT_ID = "7c2a1f4e-9b6d-4c3a-8f1e-2d5a9b0c71e3"

PROFILE = {
    "name": "Juan Peruzzo",
    "email": "juan@example.com",
    "phone": "+55 11 99999-0000",
    "cv_path": "cv-juan.pdf",
    "cover_text": "Olá, sou desenvolvedor júnior.",
}


def make_question(**overrides):
    """Base question dict; individual tests override specific fields."""
    question = {
        "key": "q_full_name",
        "label": "Nome completo",
        "field_type": "textbox",
        "required": True,
        "consent": False,
        "applicable": True,
    }
    question.update(overrides)
    return question


def ax_node(role, name, **extra):
    """Build a normalized AX node (ax_tree.snapshot_from_cdp_response shape)."""
    node = {"role": role, "name": name, "value": "", "ignored": False}
    node.update(extra)
    return node


class ReviewTaxonomyTests(unittest.TestCase):
    """Verdict per question: ANSWER / ASK / SKIP and the canonical reason."""

    def test_resolveQuestion_whenProfileHasName_shouldAnswerFromProfile(self):
        result = answer.resolve_question(make_question(), PROFILE)
        self.assertEqual(result["verdict"], answer.ANSWER)
        self.assertEqual(result["source"], "profile.name")
        self.assertEqual(result["value"], PROFILE["name"])
        self.assertIsNone(result["reason_code"])

    def test_resolveQuestion_whenProfileHasEmail_shouldAnswerFromProfile(self):
        question = make_question(key="q_email", label="E-mail")
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ANSWER)
        self.assertEqual(result["source"], "profile.email")
        self.assertEqual(result["value"], PROFILE["email"])

    def test_resolveQuestion_whenOpenTextWithoutSource_shouldAskManual(self):
        question = make_question(
            key="q_por_que", label="Por que você quer trabalhar aqui?"
        )
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertEqual(result["reason"], "no_source")
        self.assertEqual(result["reason_code"], "MANUAL")
        self.assertEqual(result["kind"], answer.KIND_OPEN_TEXT)

    def test_resolveQuestion_whenPersonalDataWithoutSource_shouldAskDadosPessoais(self):
        question = make_question(key="q_cpf", label="CPF")
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertEqual(result["reason_code"], "DADOS_PESSOAIS")
        self.assertEqual(result["kind"], answer.KIND_PERSONAL_DATA)

    def test_resolveQuestion_whenEligibilityWithoutSource_shouldAskEligibilityBlock(self):
        question = make_question(
            key="q_eligivel",
            label="Você é elegível para trabalhar no Brasil?",
        )
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertEqual(result["reason_code"], "ELIGIBILITY_BLOCK")
        self.assertEqual(result["kind"], answer.KIND_ELIGIBILITY)

    def test_resolveQuestion_whenUnrecognized_shouldAskDoubt(self):
        question = make_question(key="q_x", label="")
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertEqual(result["reason_code"], "DOUBT")
        self.assertEqual(result["kind"], answer.KIND_UNKNOWN)

    def test_resolveQuestion_whenOptionalConsent_shouldSkip(self):
        question = make_question(
            key="q_news",
            label="Aceito receber oportunidades por e-mail",
            field_type="checkbox",
            required=False,
            consent=True,
        )
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.SKIP)
        self.assertEqual(result["reason"], "optional")
        self.assertIsNone(result["reason_code"])

    def test_resolveQuestion_whenNotApplicable_shouldSkipNotApplicable(self):
        question = make_question(
            key="q_pcd", label="Descreva sua deficiência", applicable=False
        )
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.SKIP)
        self.assertEqual(result["reason"], "not_applicable")

    def test_resolveQuestion_whenRequiredConsent_shouldAskManual(self):
        question = make_question(
            key="q_termos",
            label="Li e aceito os termos",
            field_type="checkbox",
            required=True,
            consent=True,
        )
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertEqual(result["reason_code"], "MANUAL")

    def test_resolveQuestion_whenOptionalButProfileSourced_shouldAnswer(self):
        question = make_question(key="q_phone", label="Telefone", required=False)
        result = answer.resolve_question(question, PROFILE)
        self.assertEqual(result["verdict"], answer.ANSWER)
        self.assertEqual(result["source"], "profile.phone")

    def test_classifyQuestion_whenMixedPersonalAndEligibility_shouldBePersonalData(self):
        question = make_question(key="q_pcd", label="Você possui deficiência?")
        self.assertEqual(
            answer.classify_question(question), answer.KIND_PERSONAL_DATA
        )

    def test_classifyQuestion_whenOpenTextMentionsEligibility_shouldBeOpenText(self):
        question = make_question(
            key="q_exp", label="Conte sobre sua experiência profissional"
        )
        self.assertEqual(
            answer.classify_question(question), answer.KIND_OPEN_TEXT
        )


class ReviewGateTests(unittest.TestCase):
    """The submit gate: submittable only when no question resolved to ASK."""

    def _gate(self, questions):
        return answer.review_gate(questions, PROFILE)

    def test_reviewGate_whenAllGrounded_shouldBeSubmittable(self):
        questions = [
            make_question(key="q_name", label="Nome completo"),
            make_question(key="q_email", label="E-mail"),
        ]
        gate = self._gate(questions)
        self.assertTrue(gate["submittable"])
        self.assertEqual(gate["blockers"], [])
        self.assertEqual(len(gate["questions"]), 2)

    def test_reviewGate_whenOneAsk_shouldBlockSubmit(self):
        questions = [
            make_question(key="q_name", label="Nome completo"),
            make_question(
                key="q_por_que", label="Por que você quer trabalhar aqui?"
            ),
        ]
        gate = self._gate(questions)
        self.assertFalse(gate["submittable"])
        self.assertEqual(len(gate["blockers"]), 1)
        self.assertEqual(gate["blockers"][0]["reason_code"], "MANUAL")

    def test_reviewGate_whenOnlySkips_shouldBeSubmittable(self):
        questions = [
            make_question(key="q_news", label="Aceito receber ofertas",
                          field_type="checkbox", required=False, consent=True),
            make_question(key="q_pcd", label="Descreva sua deficiência",
                          applicable=False),
        ]
        gate = self._gate(questions)
        self.assertTrue(gate["submittable"])
        self.assertEqual(gate["blockers"], [])

    def test_reviewGate_whenEmpty_shouldBeSubmittable(self):
        gate = self._gate([])
        self.assertTrue(gate["submittable"])
        self.assertEqual(gate["blockers"], [])

    def test_reviewGate_whenHumanDictated_shouldBeSubmittable(self):
        questions = [
            make_question(
                key="q_por_que", label="Por que você quer trabalhar aqui?"
            ),
        ]
        gate = answer.review_gate(
            questions, PROFILE,
            human_answers={"q_por_que": "Porque admiro a empresa."},
        )
        self.assertTrue(gate["submittable"])
        self.assertEqual(gate["questions"][0]["source"], "human")
        self.assertEqual(gate["questions"][0]["value"], "Porque admiro a empresa.")


class MemoryReuseTests(unittest.TestCase):
    """Reuse of persisted answers: <memory-dir>/answers/<job_id>.json."""

    def setUp(self):
        self.memory_dir = Path(tempfile.mkdtemp(prefix="answer-memory-"))

    def tearDown(self):
        shutil.rmtree(self.memory_dir, ignore_errors=True)

    def test_resolveQuestion_whenStoredInMemory_shouldReuseFromMemory(self):
        stored = {
            "q_cpf": {
                "question_key": "q_cpf",
                "verdict": answer.ANSWER,
                "value": "123.456.789-00",
            }
        }
        question = make_question(key="q_cpf", label="CPF")
        result = answer.resolve_question(question, PROFILE, memory_entries=stored)
        self.assertEqual(result["verdict"], answer.ANSWER)
        self.assertEqual(result["source"], "memory")
        self.assertEqual(result["value"], "123.456.789-00")

    def test_saveThenLoad_shouldRoundtrip(self):
        question = make_question(key="q_cpf", label="CPF")
        result = answer.resolve_question(
            question, PROFILE, human_answers={"q_cpf": "123.456.789-00"}
        )
        answer.save_answers(
            self.memory_dir, JOB_ID, [result], attempt_id=ATTEMPT_ID,
        )
        entries = answer.load_answers(self.memory_dir, JOB_ID)
        self.assertIn("q_cpf", entries)
        self.assertEqual(entries["q_cpf"]["value"], "123.456.789-00")
        self.assertEqual(entries["q_cpf"]["source"], "human")

    def test_resolveQuestion_whenProfileWinsOverMemory_shouldUseProfile(self):
        stored = {
            "q_full_name": {
                "question_key": "q_full_name",
                "verdict": answer.ANSWER,
                "value": "Nome antigo",
            }
        }
        result = answer.resolve_question(
            make_question(), PROFILE, memory_entries=stored
        )
        self.assertEqual(result["source"], "profile.name")
        self.assertEqual(result["value"], PROFILE["name"])

    def test_loadAnswers_whenMissing_shouldReturnEmpty(self):
        self.assertEqual(answer.load_answers(self.memory_dir, "unknown-job"), {})

    def test_loadAnswers_whenCorrupt_shouldReturnEmpty(self):
        answers_dir = self.memory_dir / answer.ANSWERS_SUBDIR
        answers_dir.mkdir(parents=True, exist_ok=True)
        (answers_dir / f"{JOB_ID}.json").write_text(
            "not json {{{", encoding="utf-8"
        )
        self.assertEqual(answer.load_answers(self.memory_dir, JOB_ID), {})

    def test_resolveQuestion_whenMemoryValueHasWhitespace_shouldStrip(self):
        # PR #80 review P1 — persisted values are normalized on reuse.
        stored = {
            "q_cpf": {
                "question_key": "q_cpf",
                "verdict": answer.ANSWER,
                "value": "  123.456.789-00  ",
            }
        }
        result = answer.resolve_question(
            make_question(key="q_cpf", label="CPF"), PROFILE,
            memory_entries=stored,
        )
        self.assertEqual(result["source"], "memory")
        self.assertEqual(result["value"], "123.456.789-00")

    def test_resolveQuestion_whenMemoryValueBlanksAfterStrip_shouldNotReuse(self):
        # PR #80 review P1 — a stored value that collapses to blank is treated
        # as ABSENT: reuse must not resurrect whitespace-only text.
        stored = {
            "q_cpf": {"question_key": "q_cpf", "verdict": answer.ANSWER,
                      "value": "   "},
        }
        result = answer.resolve_question(
            make_question(key="q_cpf", label="CPF"), PROFILE,
            memory_entries=stored,
        )
        self.assertEqual(result["verdict"], answer.ASK)
        self.assertNotEqual(result["source"], "memory")

    def test_resolveQuestion_whenHumanDictationHasWhitespace_shouldStrip(self):
        # PR #80 review P1 — dictated values are normalized before storing.
        result = answer.resolve_question(
            make_question(key="q_cpf", label="CPF"), PROFILE,
            human_answers={"q_cpf": "  123.456.789-00 "},
        )
        self.assertEqual(result["source"], "human")
        self.assertEqual(result["value"], "123.456.789-00")

    def test_saveAnswers_whenSameJobTwice_shouldOverwrite(self):
        first = make_question(key="q_cpf", label="CPF")
        answer.save_answers(
            self.memory_dir, JOB_ID,
            [answer.resolve_question(
                first, PROFILE, human_answers={"q_cpf": "111"})],
        )
        second = make_question(
            key="q_por_que", label="Por que você quer trabalhar aqui?"
        )
        answer.save_answers(
            self.memory_dir, JOB_ID,
            [answer.resolve_question(
                second, PROFILE, human_answers={"q_por_que": "x"})],
        )
        entries = answer.load_answers(self.memory_dir, JOB_ID)
        self.assertNotIn("q_cpf", entries)
        self.assertIn("q_por_que", entries)


class QuestionExtractionTests(unittest.TestCase):
    """AX nodes → question dicts (deterministic, marker-based)."""

    def test_extractQuestions_whenRequiredTextbox_shouldBeRequired(self):
        nodes = [ax_node("textbox", "CPF *", backendNodeId=11)]
        questions = answer.extract_questions(nodes)
        self.assertEqual(len(questions), 1)
        self.assertTrue(questions[0]["required"])
        self.assertEqual(questions[0]["field_type"], "textbox")

    def test_extractQuestions_whenOptionalMarker_shouldBeOptional(self):
        nodes = [ax_node("textbox", "LinkedIn (opcional)", backendNodeId=12)]
        questions = answer.extract_questions(nodes)
        self.assertFalse(questions[0]["required"])

    def test_extractQuestions_whenChoiceWithoutMarker_shouldBeOptional(self):
        nodes = [ax_node("radio", "Cargo atual", backendNodeId=13)]
        questions = answer.extract_questions(nodes)
        self.assertFalse(questions[0]["required"])

    def test_extractQuestions_whenConsentCheckbox_shouldInferConsent(self):
        nodes = [ax_node("checkbox", "Aceito receber newsletter", backendNodeId=14)]
        questions = answer.extract_questions(nodes)
        self.assertTrue(questions[0]["consent"])
        self.assertEqual(
            answer.classify_question(questions[0]), answer.KIND_CONSENT
        )

    def test_extractQuestions_whenNonInputNode_shouldIgnore(self):
        nodes = [
            ax_node("heading", "Candidatura"),
            ax_node("button", "Enviar candidatura"),
            ax_node("link", "Termos de uso"),
        ]
        self.assertEqual(answer.extract_questions(nodes), [])

    def test_extractQuestions_whenDuplicateLabels_shouldDedupeKeys(self):
        nodes = [
            ax_node("textbox", "E-mail", backendNodeId=21),
            ax_node("textbox", "E-mail", backendNodeId=22),
        ]
        questions = answer.extract_questions(nodes)
        self.assertEqual(questions[0]["key"], "q_e-mail")
        self.assertEqual(questions[1]["key"], "q_e-mail_2")

    def test_extractQuestions_whenLabelHasPunctuation_shouldCleanKey(self):
        nodes = [ax_node("textbox", "Por que você quer trabalhar aqui? *",
                         backendNodeId=23)]
        questions = answer.extract_questions(nodes)
        self.assertEqual(questions[0]["key"],
                         "q_por que você quer trabalhar aqui")


class CanonicalCodeTests(unittest.TestCase):
    """Canonical attempt codes — the exact strings, nothing else."""

    def test_blockReasons_shouldBeExactCanonicalSet(self):
        self.assertEqual(
            answer.BLOCK_REASONS,
            frozenset({"MANUAL", "DADOS_PESSOAIS", "ELIGIBILITY_BLOCK", "DOUBT"}),
        )

    def test_blockerReason_shouldMapKindsToCanonCodes(self):
        cases = [
            (make_question(label="", key="q_x"), "DOUBT"),
            (make_question(label="CPF", key="q_cpf"), "DADOS_PESSOAIS"),
            (make_question(label="Você é elegível?", key="q_elig"),
             "ELIGIBILITY_BLOCK"),
            (make_question(label="Por que você?", key="q_why"), "MANUAL"),
            (make_question(label="LinkedIn URL", key="q_li"), "MANUAL"),
            (make_question(label="Li e aceito os termos", key="q_termos",
                           consent=True), "MANUAL"),
        ]
        for question, expected in cases:
            self.assertEqual(answer.blocker_reason(question), expected, question)

    def test_blockerCodes_shouldBeDistinctFirstSeen(self):
        blockers = [
            {"reason_code": "DADOS_PESSOAIS"},
            {"reason_code": "MANUAL"},
            {"reason_code": "DADOS_PESSOAIS"},
        ]
        self.assertEqual(
            answer.blocker_codes(blockers), ["DADOS_PESSOAIS", "MANUAL"]
        )

    def test_canonicalBlockReason_shouldJoinWithPlus(self):
        blockers = [
            {"reason_code": "DADOS_PESSOAIS"},
            {"reason_code": "MANUAL"},
        ]
        self.assertEqual(answer.canonical_block_reason(blockers),
                         "DADOS_PESSOAIS+MANUAL")


class BlockedSubmitTests(unittest.TestCase):
    """PR #80 review P0-2 — the review gate WRITES its outcome: a blocked
    submit becomes an INCOMPLETE attempt through the single record path
    (verdict.decide) with the canonical block reason — NEVER an applied
    record (a blocked submit must never look applied to idempotency)."""

    def setUp(self):
        self.memory_dir = Path(tempfile.mkdtemp(prefix="answer-blocked-"))

    def tearDown(self):
        shutil.rmtree(self.memory_dir, ignore_errors=True)

    def test_recordBlockedSubmit_whenBlocked_shouldWriteIncompleteAttempt(self):
        blockers = [{"reason_code": "DADOS_PESSOAIS"},
                    {"reason_code": "MANUAL"}]
        result = answer.record_blocked_submit(
            self.memory_dir,
            job_id=JOB_ID,
            attempt_id=ATTEMPT_ID,
            job_url="https://jobs.gupy.io/jobs/8472",
            portal="gupy",
            blockers=blockers,
        )
        self.assertEqual(result["outcome"], verdict.INCOMPLETE)
        self.assertEqual(result["reason"], "DADOS_PESSOAIS+MANUAL")
        self.assertFalse(result["written_applied"])
        self.assertTrue(result["written_attempt"])
        attempts = list((self.memory_dir / "attempts" / ATTEMPT_ID).rglob("*.json"))
        self.assertEqual(len(attempts), 1)
        data = json.loads(attempts[0].read_text(encoding="utf-8"))
        self.assertEqual(data["outcome"], verdict.INCOMPLETE)
        self.assertEqual(data["reason"], "DADOS_PESSOAIS+MANUAL")
        self.assertFalse(data["verdict"]["submitted"])
        # No applied record — a blocked submit must never look applied.
        self.assertFalse(
            (self.memory_dir / "applications" / f"{JOB_ID}.json").exists())

    def test_recordBlockedSubmit_whenSingleBlocker_shouldUseCanonicalCode(self):
        result = answer.record_blocked_submit(
            self.memory_dir,
            job_id=JOB_ID,
            attempt_id=ATTEMPT_ID,
            job_url="https://jobs.gupy.io/jobs/8472",
            portal="gupy",
            blockers=[{"reason_code": "ELIGIBILITY_BLOCK"}],
        )
        self.assertEqual(result["outcome"], verdict.INCOMPLETE)
        self.assertEqual(result["reason"], "ELIGIBILITY_BLOCK")
        self.assertFalse(result["written_applied"])


class CorruptJobIdRefusalTests(unittest.TestCase):
    """Issue #82 — answer.py persists answers/<job_id>.json keyed by the same
    derived id; a job_id carrying the '.' (hence '...') corruption marker must
    be refused, writing nothing (a corrupt key aliases jobs in the audit)."""

    CORRUPT_ID = "eyJqb2...sIn0="
    GENUINE_ID = ("eyJpZCI6Ijc1NTY5NDM3NSIsInRpdGxlIjoi"
                  "ZGVzZW52b2x2ZWRvci1qYXZhIn0=")

    def setUp(self):
        self.memory_dir = Path(tempfile.mkdtemp(prefix="answer-corrupt-"))

    def tearDown(self):
        shutil.rmtree(self.memory_dir, ignore_errors=True)

    def test_saveAnswers_whenJobIdCorrupt_shouldRefuse(self):
        question = make_question()
        resolved = [answer.resolve_question(question, PROFILE)]
        result = answer.save_answers(
            self.memory_dir, self.CORRUPT_ID, resolved, attempt_id=ATTEMPT_ID,
        )
        self.assertEqual(result["ok"], False)
        self.assertEqual(result["error"], "corrupt_gupy_slug")
        self.assertEqual(list(self.memory_dir.rglob("*.json")), [])

    def test_recordBlockedSubmit_whenJobIdCorrupt_shouldWriteNothing(self):
        # record_blocked_submit routes through verdict.decide — the attempt
        # writer refuses the corrupt id, so nothing is persisted.
        result = answer.record_blocked_submit(
            self.memory_dir,
            job_id=self.CORRUPT_ID,
            attempt_id=ATTEMPT_ID,
            job_url="https://mendelics.gupy.io/job/eyJqb2...sIn0=",
            portal="gupy",
            blockers=[{"reason_code": "MANUAL"}],
        )
        self.assertFalse(result["written_applied"])
        self.assertFalse(result["written_attempt"])
        self.assertEqual(list(self.memory_dir.rglob("*.json")), [])

    def test_saveAnswers_whenJobIdGenuineBase64_shouldSave(self):
        question = make_question()
        resolved = [answer.resolve_question(question, PROFILE)]
        result = answer.save_answers(
            self.memory_dir, self.GENUINE_ID, resolved, attempt_id=ATTEMPT_ID,
        )
        self.assertEqual(result["job_id"], self.GENUINE_ID)
        self.assertTrue(
            (self.memory_dir / "answers" / f"{self.GENUINE_ID}.json").is_file())

    def test_saveAnswers_whenInfojobsNumericSlug_shouldSave(self):
        question = make_question()
        resolved = [answer.resolve_question(question, PROFILE)]
        result = answer.save_answers(
            self.memory_dir, "755694375", resolved, attempt_id=ATTEMPT_ID,
        )
        self.assertEqual(result["job_id"], "755694375")
        self.assertTrue(
            (self.memory_dir / "answers" / "755694375.json").is_file())


if __name__ == "__main__":
    unittest.main()