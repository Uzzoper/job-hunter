# Spec: ATS Resume Tailoring (PDF)

> **Layer:** `web` | `application` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase`
> - `com.juanperuzzo.job_hunter.application.service.ResumeTailoringService`
> - `com.juanperuzzo.job_hunter.infrastructure.pdf.ResumePdfRenderer`
> - `com.juanperuzzo.job_hunter.web.controller.JobController` (new endpoint)
> - `src/main/resources/resume/ats-template.html` (static format template)
> - `src/main/resources/fonts/DejaVuSans.ttf` (bundled font, PT-BR accents)
> **Corresponding tests:** `ResumeTailoringServiceTest.java`, `ResumePdfRendererTest.java`, `JobControllerResumeTest.java`
> **Prompt:** Prompt 4 — must also be added to `docs/specs/prompts.md` (project rule: prompts are documented there first)

---

## Problem

The user uploads a resume (PDF → `resumeText` in `UserProfile`) and gets AI job analysis
(`JobAnalysis` with `matchedSkills`/`missingSkills`). What is missing: a **tailored version of
the resume per job** that maximizes ATS (Applicant Tracking System) keyword matching — while
**keeping the exact same visual format** as the current resume and **never inventing facts**.

## Approach

1. **AI tailors content** — a new prompt (Prompt 4) receives `resumeText` + job + analysis and
   returns structured JSON (same sections as the current resume, content optimized for ATS).
2. **Format is preserved by a static HTML/CSS template** — `ats-template.html` reproduces the
   current resume layout (single column, section headers, grouped skills, bullets). The template
   never changes; only the injected content changes. This is the only robust way to keep the
   format identical — editing the original PDF in place (PDFBox text replacement) is fragile and
   breaks when content length changes.
3. **PDF rendering** — `io.github.openhtmltopdf:openhtmltopdf-pdfbox` (community fork, PDFBox 3
   compatible — the project already uses PDFBox 3.0.1). Pure Java, LGPL, no external binaries.
4. **Honesty guard (backend)** — any skill returned by the AI that does not appear in the
   original `resumeText` is **dropped** with a logged warning. The AI is instructed to use exact
   skill names from the resume, so legitimate skills are never dropped.

---

## Expected behavior

### Scenario 1: Generate tailored resume PDF
- **GIVEN** an authenticated user with a `UserProfile` containing `resumeText` and an existing
  `JobAnalysis` for the job
- **WHEN** they call `POST /api/jobs/{id}/resume`
- **THEN** the backend builds Prompt 4 (resume text truncated to `ai.resume-tailoring.max-chars`)
- **AND** sends it to the AI via `AiPort`
- **AND** parses the JSON response, dropping any invented skills (not found in `resumeText`)
- **AND** fills `ats-template.html` with the tailored content
- **AND** renders a PDF via `ResumePdfRenderer`
- **AND** returns `200 OK` with `Content-Type: application/pdf` and
  `Content-Disposition: attachment; filename="curriculo-{jobId}.pdf"`

### Scenario 2: No profile / no resume text
- **GIVEN** an authenticated user without a `UserProfile` or with blank `resumeText`
- **WHEN** they call `POST /api/jobs/{id}/resume`
- **THEN** returns the existing `ProfileNotConfiguredException` (same behavior as email generation)

### Scenario 3: No analysis for the job
- **GIVEN** an authenticated user with a profile but no `JobAnalysis` for the job
- **WHEN** they call `POST /api/jobs/{id}/resume`
- **THEN** returns the existing `AnalysisNotFoundException` (analysis is a prerequisite — the
  tailoring needs `matchedSkills`/`missingSkills`)

### Scenario 4: AI returns invalid JSON
- **GIVEN** a valid request
- **WHEN** the AI response is not valid JSON or lacks required fields
- **THEN** returns `502 Bad Gateway` (`AiException`), same as `ResumeUploadService`

### Scenario 5: AI invents a skill
- **GIVEN** a valid request
- **WHEN** the AI response contains a skill not present in the original `resumeText`
  (case-insensitive substring check)
- **THEN** the skill is dropped from the response, a warning is logged, and the PDF is generated
  without it (no error — the resume must never contain fabricated skills)

---

## Business rules

- **Format preservation:** The PDF layout is defined exclusively by `ats-template.html`. The AI
  never controls layout — only content. The tailored PDF must look like the current resume.
- **Honesty:** The AI may only reorder, rephrase, and emphasize existing content. It must never
  invent skills, companies, roles, dates, degrees, courses, or projects. The backend skill guard
  (Scenario 5) is the enforcement layer.
- **Skill ordering:** Within each skills group, skills matched to the job come first, then the
  rest (original order preserved).
- **Keyword usage:** Bullets may be rephrased to include keywords from the job description **only
  when those keywords truthfully describe existing content** (e.g. "Spring Boot" bullet may say
  "REST APIs with Spring Boot" if the original says "Spring Boot").
- **AI prompt truncation:** `resumeText` longer than `ai.resume-tailoring.max-chars` (default
  8000) is truncated before being sent to the AI, with a logged warning (same pattern as
  `ResumeUploadService`).
- **No persistence:** The tailored PDF is generated on demand and returned as bytes. Nothing is
  written to disk or to the database.
- **Language:** The resume is in Brazilian Portuguese — the AI must keep the output in the same
  language as the original resume.
- **Sections:** The JSON response must contain all sections of the current resume (objective,
  skills groups, projects, experience, education, courses, languages, differentials). Static
  sections (education, courses, languages) are passed through unchanged.

---

## Interface contract

### HTTP — `JobController`

| Method | Path | Auth | Content-Type | Response |
|--------|------|------|-------------|----------|
| POST | `/api/jobs/{id}/resume` | Bearer | — | `200` + `application/pdf` (attachment `curriculo-{jobId}.pdf`) |

### New application service

```java
public class ResumeTailoringService {
    byte[] tailorResume(Long userId, Long jobId);
}
```

### New use case port

```java
public interface TailorResumeUseCase {
    byte[] tailorResume(Long userId, Long jobId);
}
```

### New infrastructure component

```java
public class ResumePdfRenderer {
    byte[] render(String html);  // OpenHTMLToPDF → PDF bytes
}
```

### Existing ports used

- `AiPort` — Prompt 4 completion
- `UserProfileRepository` — load `resumeText`
- `JobAnalysisRepository` — load analysis (matched/missing skills)
- `JobRepository` — load job (title, company, description)

---

## New AI prompt (Prompt 4: Resume tailoring)

**Used in:** `ResumeTailoringService`
**Model:** same as Prompt 1/2 (MiniMax M2.5 via OpenRouter, or Ollama)
**Expected response:** plain JSON (no markdown, no text before or after)

```
You are a career assistant that tailors resumes for Applicant Tracking Systems (ATS).

Rewrite the resume below for the target job. Keep the EXACT same sections and structure.
Return ONLY a valid JSON object, with no markdown and no additional text.

Response format:
{
  "objective": "<2-3 lines, keyword-rich, tailored to the role>",
  "skills": {
    "languages": ["skill 1", "..."],
    "frameworks": ["skill 1", "..."],
    "databases": ["skill 1", "..."],
    "cloudDevOps": ["skill 1", "..."],
    "tools": ["skill 1", "..."],
    "concepts": ["skill 1", "..."]
  },
  "projects": [
    {
      "name": "<project name>",
      "bullets": ["<bullet 1>", "..."],
      "link": "<url or empty string>"
    }
  ],
  "experience": [
    {
      "role": "<role>",
      "company": "<company>",
      "period": "<period>",
      "bullets": ["<bullet 1>", "..."]
    }
  ],
  "education": [
    { "degree": "<degree>", "institution": "<institution>", "status": "<status>" }
  ],
  "courses": ["<course 1>", "..."],
  "languages": [
    { "language": "<language>", "level": "<level>" }
  ],
  "differentials": ["<differential 1>", "..."]
}

MANDATORY RULES:
1. NEVER invent skills, companies, roles, dates, degrees, courses, projects, or links.
   Only reorder, rephrase, and emphasize content that already exists in the resume.
2. Use the EXACT skill names from the resume (do not rename or rephrase skills).
3. Within each skills group, put the skills the candidate has for this role FIRST,
   then the remaining skills in their original order.
4. Rewrite bullets to include keywords from the job description ONLY when those
   keywords truthfully describe existing content. Never stretch the truth.
5. Reorder projects by relevance to the job (most relevant first). Keep all projects.
6. Keep the same language as the original resume.
7. Keep all sections. Education, courses, and languages are passed through unchanged.
8. The objective must mention the target role and the candidate's strongest relevant
   skills, without inventing anything.

Candidate skills for this role: {{MATCHED_SKILLS}}
Skills the candidate lacks (do NOT add them to the resume): {{MISSING_SKILLS}}

Job listing:
Title: {{JOB_TITLE}}
Company: {{COMPANY}}
Description: {{JOB_DESCRIPTION}}

Resume text:
{{RAW_RESUME_TEXT}}
```

### Prompt variables

| Variable | Source | Example |
|---|---|---|
| `{{RAW_RESUME_TEXT}}` | `UserProfile.resumeText` (truncated to max-chars) | "JUAN ANTONIO PERUZZO..." |
| `{{JOB_TITLE}}` | `job.title()` | "Desenvolvedor Java Júnior" |
| `{{COMPANY}}` | `job.company()` | "CompanyX" |
| `{{JOB_DESCRIPTION}}` | `job.description()` | full job description text |
| `{{MATCHED_SKILLS}}` | `analysis.matchedSkills()` | "Java, Spring Boot, REST" |
| `{{MISSING_SKILLS}}` | `analysis.missingSkills()` | "Kubernetes, AWS" |

---

## Configuration

```yaml
ai:
  resume-tailoring:
    max-chars: 8000    # max chars of resume text sent to AI; longer text is truncated with a warning
```

### New dependency (pom.xml)

```xml
<dependency>
    <groupId>io.github.openhtmltopdf</groupId>
    <artifactId>openhtmltopdf-pdfbox</artifactId>
    <version>1.1.73</version>
</dependency>
```

> The `io.github.openhtmltopdf` group is the community fork compatible with **PDFBox 3**
> (the project uses PDFBox 3.0.1). The original `com.openhtmltopdf` 1.0.x line uses PDFBox 2
> and would conflict.

### Font

Bundle `DejaVuSans.ttf` (free license, full PT-BR accent support) at
`src/main/resources/fonts/DejaVuSans.ttf` and register it in `ResumePdfRenderer` via
`builder.useFont(...)`. The template must reference it as the default font family.

---

## Error cases

| Situation | Exception | HTTP |
|-----------|-----------|------|
| No profile or blank `resumeText` | `ProfileNotConfiguredException` | per existing handler |
| No analysis for the job | `AnalysisNotFoundException` | per existing handler |
| Job not found | existing job exception | per existing handler |
| AI call fails | `AiException` | 502 Bad Gateway |
| AI returns invalid JSON / missing fields | `AiException` | 502 Bad Gateway |
| PDF rendering failure | `IllegalStateException` | 500 Internal Server Error |

---

## Tests (TDD — RED → GREEN → REFACTOR)

### `ResumeTailoringServiceTest` (unit, Mockito — no Spring context)

- `tailorResume_whenNoProfile_shouldThrowProfileNotConfigured()`
- `tailorResume_whenNoAnalysis_shouldThrowAnalysisNotFound()`
- `tailorResume_whenAiReturnsInvalidJson_shouldThrowAiException()`
- `tailorResume_whenAiReturnsInventedSkill_shouldDropSkill()` — skill not in `resumeText` is
  removed from the rendered content
- `tailorResume_whenResumeTextTooLong_shouldTruncatePrompt()` — captured prompt contains
  truncated text + warning logged
- `tailorResume_whenValid_shouldReturnPdfBytes()` — PDF bytes are non-empty and PDFBox text
  extraction of the result contains the tailored objective

### `ResumePdfRendererTest` (unit)

- `render_whenValidHtml_shouldProducePdfWithText()` — PDFBox extraction contains the injected text
- `render_whenHtmlHasAccents_shouldRenderAccents()` — PT-BR accents render correctly (font check)

### `JobControllerResumeTest` (`@WebMvcTest` + MockMvc)

- `generateResume_whenAuthenticated_shouldReturnPdfAttachment()` — status 200, content type
  `application/pdf`, `Content-Disposition` attachment with `curriculo-{jobId}.pdf`

---

## Out of scope

- Persisting tailored resumes (history) — generated on demand only
- Editing the original PDF in place (template approach chosen instead)
- `.docx` or other output formats
- CLI/TUI integration (download command can be added later)
- Multiple resume templates per user
- OCR for image-based PDFs