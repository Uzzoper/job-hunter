# ATS-tailored resume generation

Used when the user asks to adapt their resume to a specific posting ("converter meu
curriculo para a ATS dessa vaga"). The deliverable is a **file**, not an
application — the submission stays manual.

## Generate via the backend, never by hand

**The project already owns resume generation. Use it.** `POST /api/jobs/{id}/resume`
(`TailorResumeUseCase.tailorResume` → `ResumeTailoringService`) is the single
supported path, and it tails the resume to the posting because it consumes the
stored `resumeText` plus that job's description through the same `AiPort` every
other AI feature uses.

Hand-rolling a generator (reportlab, LaTeX, fpdf) is a defect, not a shortcut.
**The exception is the user's own existing LaTeX source.** When the user supplies
a resume and says "follow this pattern", editing that source and recompiling is
not hand-rolling — it is the better artifact, and it can beat a fresh generator on
link annotations. Check for a `.tex` beside the PDF before assuming none exists:
`pdfinfo` reporting `Producer: LaTeX with hyperref` means a compilable source is
recoverable (`tectonic Resume.tex`, no rebuild of the project). Away from that
exception:

- it bypasses the `AiPort` the whole backend is built around, so the output is
  untailored — a static script has no access to the posting it is supposedly
  tailoring for;
- it lands in `~/.hermes/profiles/jobhunter-bot/cache/scratch/`, which is pruned
  after 24h, so the generator is gone by the time anyone notices a defect;
- it drops the clickable-link layer the endpoint emits, so the emailed PDF ships
  with GitHub/LinkedIn/portfolio as plain text.

```bash
TOKEN=$(grep BOT_SERVICE_API_KEY <job-hunter-repo>/.env | cut -d= -f2)

# The endpoint is gated on analysis: run /analyze FIRST or /resume answers
# 400 "Job must be analyzed before tailoring the resume".
curl -s -X POST "http://127.0.0.1:8080/api/jobs/$JOB/analyze" \
  -H "X-Bot-Token: $TOKEN"

curl -s -X POST "http://127.0.0.1:8080/api/jobs/$JOB/resume" \
  -H "X-Bot-Token: $TOKEN" -o out.pdf -w "HTTP=%{http_code}\n"
```

`/analyze` is a hard prerequisite of `/resume` (same gate shape as
`/email`); treat that 400 as an instruction, not a defect. A fast-failing
`/analyze` means the AI path is down — see `job-hunter-stack-debug` for the
gateway diagnosis rather than retrying.

**Verify the artifact before offering it.** Confirm the page count, that
`pdftotext` extracts the body, and that the contact URLs are real annotations:

```bash
pdftotext out.pdf - | head -40
python3 -c "d=open('out.pdf','rb').read(); print('Link:',d.count(b'/Link'),'URI:',d.count(b'/URI'))"
```

## Editing the LaTeX source: compile and verify like a build

`tectonic -X compile <file>.tex --outdir <dir>` fails with
`output directory does not exist` when the dir is missing — `mkdir -p` it first.
The compile is fast and needs no network after the first bundle fetch, so editing
the source is cheap to iterate on.

Verify the edit with **three** checks, not one, because a compile that succeeds
can still ship a regression:

1. **Page count** — `pdfinfo <out>.pdf | grep Pages`. Two A4 pages is the target
   for a junior application; three means content spilled and the densest lines
   went with it.
2. **Link annotations, via the decompressing verifier** — the count must not
   regress from the pre-edit PDF. Byte-grepping `/Link` reports `0` on a perfectly
   good file and will tell you the edit destroyed links that are still there.
3. **Corrected claims** — grep the extracted text for each superseded claim (see
   the profile section below). A source edit that leaves the old claim in place
   reproduces the exact error the user withdrew.

`pdffonts` on a good build shows embedded subsets with `emb yes` and `uni yes`
(`IHAIZV+LMSans10-Bold` and friends). If a change makes fonts non-embedded, that
is a defect — do not trade it for file size.

Two edits worth making once at the source rather than per application:

- **De-duplicate the skills list.** The same tool repeated under two headings
  (`Git, Linux, Postman…` under both *Ferramentas* and *Conceitos*) burns the
  densest lines of a two-page document. Merge them once.
- **Put the location in the header line.** `Ponta Grossa/PR` belongs next to the
  phone: location is a field ATS extracts, and the header line is the only part
  every parser reads before it decides where to look.

**Leave the factual claims alone unless the user corrects them.** A degree listed
as `trancado (6/10 semestres cursados)` is already honest — do not "clean it up"
into a completed degree, and do not delete it unprompted either; it is their CV.

## Keep the profile honest — it is the source every AI feature reads

`/resume`, `/email` and `/analyze` all consume the profile's stored
`resumeText`. A correction the user makes to you in chat changes **nothing**
downstream until it is pushed into the profile, so a corrected email body with an
uncorrected PDF attached reproduces the exact claim they withdrew.

```bash
TOKEN=$(grep BOT_SERVICE_API_KEY <job-hunter-repo>/.env | cut -d= -f2)

curl -s -X POST "http://127.0.0.1:8080/api/profile/upload-resume" \
  -H "X-Bot-Token: $TOKEN" \
  -F "file=@<resume-dir>/<resume-filename>-ATS.pdf;type=application/pdf"

curl -s "http://127.0.0.1:8080/api/profile" -H "X-Bot-Token: $TOKEN"
```

Then **assert the corrections against the returned `resumeText` yourself** —
degree not marked completed, no framework the user never used, no subscription
billing, remote-since phrasing present — before reporting success. A grep of the
extracted PDF text is the same check from the artifact side.

**After any profile change, re-analyze before ranking.** Stored `matchScore`
values were computed against the old profile, so they are stale in both
directions: making a profile honest deflates inflated matches sharply, and a
"best match" can turn out to be presencial. Re-run `/analyze` across the
shortlist and report the new numbers; never present a ranking computed before a
profile edit.

## Start from the stored resume, not from questions

The candidate's real data is already on disk at
`~/.hermes/profiles/jobhunter-bot/<resume-filename>.pdf` (the SOUL
`{{RESUME_FILENAME}}` placeholder), with a maintained LaTeX
source at `<resume-dir>/<resume-filename>-ATS.tex` (compile with `tectonic`;
that build is the validated ATS artifact). Read one with `read_file` (it
auto-extracts the text layer) to know what is true before you write about it.

Prefer the maintained `.tex` as the baseline whenever the target is
format-preserving: it carries `[hidelinks]` (clickable URLs that still read as
visible text) and beats the endpoint's generator on link annotations. Reuse it
per posting by adjusting the objective line and keyword order — do not rebuild the
layout.

**Do not block on a re-ask of data you already hold.** If the user says
"pode se baser no meu curriculo que vc ja tem", that is authorization to proceed
from the stored PDF — asking again wastes the turn. Ask only about what the
posting demands and the resume genuinely does not contain.

## ATS-safe layout (this is the part that actually gets rejected)

The endpoint produces this; the checks below are how you confirm it did rather
than rebuild it by hand.

- **Single column.** No tables, no multi-column, no sidebars.
- **No text boxes, no headers/footers, no graphics, no icons.**
- **Standard font** (Helvetica), text as real paragraphs — not canvas drawing.
- **Contact line as plain text** at the top; nothing may separate name from
  email for a naive parser.
- **Exactly one page.** A junior resume that spills two lines onto page 2 reads
  as careless.
- **Keep the PDF extractable.** Generate, then *prove* it:
  ```bash
  pdftotext out.pdf - | head -60
  ```
  If `pdftotext` returns the content, the ATS will too. Re-check after any trim
  to one page by counting pages directly from the file
  (`data.count(b'/Type /Page') - data.count(b'/Type /Pages')`).
- **Contact URLs must carry annotations — but count them the right way.** A PDF
  whose GitHub/LinkedIn/portfolio appear only as visible text reads as a stale or
  hand-built artifact. **However, do NOT diagnose this by grepping the raw bytes
  for `/Link` and `/URI`**: those objects live in *compressed object streams*, so a
  PDF carrying a full set of working hyperlinks still reports `0` and `0`.
  Reporting a correct resume as link-less on that evidence is a false accusation —
  retract it explicitly rather than quietly moving on. Use the verifier, which
  decompresses the streams and counts real annotations:

  ```bash
  python3 skills/productivity/job-application-prep/scripts/verify_resume_pdf.py out.pdf \
      --forbid Django --forbid assinatura
  ```

  Judge from that output plus whether `pdftotext` shows URLs **truncated** to bare
  labels (`github` alone, `linkedin.com/in` cut mid-token). Truncation is the real
  signal of a weak generator — LaTeX with `[hidelinks]` produces clickable links
  *and* keeps the URL visible as text for the parser, which is the target.

Two extraction hazards to watch while building the generator: a non-breaking
space or a stray escape landing mid-sentence makes the extracted text read
`web React. Next.js` — inspect the extracted output, not the rendered PDF.

## Mirror the posting's own language

Reuse its noun phrases verbatim — *single page applications (SPA)*, *backend API
development*, *relational database storage and retrieval*, *automated unit /
integration / end-to-end testing*, *release processes*, *technical
documentation*. ATS matching is lexical, not semantic.

**Write in the posting's language.** An English posting with an English form is
matched against an English resume; the reverse also holds. Match the document's
language rather than the resume's original language.

## Re-frame, never invent

Tailoring means reordering and reframing real experience in the posting's terms —
not adding capability. Redirect bullet emphasis toward the parts of existing work
that answer the requirements, and drop projects that serve none of them.

Where the posting asks for something the candidate lacks, say so plainly in the
report rather than implying coverage. Naming a gap the candidate can defend in
an interview beats a keyword match they cannot.

## Credential fields are a separate decision — and they block the artifact

The hard rule in the parent SKILL.md applies with full force: never infer a
graduation year from an expected completion date, never pick the
most-institution-sounding name off the CV, never attach a transcript whose
existence the user has not confirmed. Ask for these **in one batch, up front**,
because the tailoring is blocked on them anyway.

Fields an ATS form requires that a resume cannot answer:

- which university (required, and cross-checked against the transcript)
- what year did you graduate — a degree in progress has no such year
- **a mandatory transcript upload** — if the document does not exist the
  application cannot be completed at all, which is worth surfacing before
  anything else is produced.

Most junior postings hedge with "**or equivalent experience**". When the posting
allows it, that is the candidate's real route and belongs in the report as the
main weapon, with a cover letter aimed at exactly that field.

## Do not submit

Produce the file, report the open credential questions, and stop. Uploading and
sending on an external ATS is manual — the same read-only boundary that governs
LinkedIn apply governs the submission step here.