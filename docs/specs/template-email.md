# Spec: Template email for high-match jobs

> **Layer:** `application` (service)  
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.TemplateEmailService`  
> **Corresponding test:** `TemplateEmailServiceTest.java`  
> **Depends on:** `generate-email.md` (called by `EmailGenerationService` when `matchScore >= threshold`), `user-profile.md` (profile is the data source), `profile-placeholders.md` (placeholder resolution rules)

---

## Context

Jobs with `matchScore >= minMatchScore` (default: 60) do not need AI-personalized emails. Instead, a fixed template with the candidate's personal introduction and portfolio is used. This saves AI credits and sends faster.

`TemplateEmailService` is a stateless builder — it returns a `TemplateResult` record with subject and body. Persistence is owned by `EmailGenerationService`, which calls this service and saves the draft through the same upsert path used by the AI branch.

All candidate personal data (name, phone, portfolio, GitHub, LinkedIn, email, skills, projects) comes from the caller-provided `User` + `UserProfile` via `{{PLACEHOLDERS}}` — nothing is hardcoded in source.

---

## Template

### Subject

```
Candidatura — {{JOB_TITLE}} na {{COMPANY}}
```

### Body (Portuguese)

```
Olá. Tudo bem?

Gostaria de me candidatar à vaga de {{JOB_TITLE}} na {{COMPANY}}.

Sou desenvolvedor back-end focado no ecossistema Java/Spring, com projetos em produção construídos com Java, Spring Boot, APIs REST, Git e bancos de dados relacionais.

Além dos requisitos da vaga, trabalho também com {{SKILLS}}.

Alguns destaques do meu portfólio:

{{PROJECTS}}

Segue meu currículo em anexo. Podemos agendar uma conversa para eu mostrar esses projetos rodando?

Atenciosamente,

{{CANDIDATE_NAME}}
{{PHONE}}
E-mail: {{CANDIDATE_EMAIL}}
Portfólio: {{PORTFOLIO_URL}}
GitHub: {{GITHUB_URL}}
LinkedIn: {{LINKEDIN_URL}}
```

### Placeholders

| Placeholder | Source | Missing-data rule |
|---|---|---|
| `{{JOB_TITLE}}`, `{{COMPANY}}` | `Job.title()`, `Job.company()` | n/a (always present) |
| `{{CANDIDATE_NAME}}` | `User.name()` | fail (profile missing → `ProfileNotFoundException`) |
| `{{CANDIDATE_EMAIL}}` | `User.email()` | omit line |
| `{{PHONE}}` | `UserProfile.phone()` | omit line |
| `{{PORTFOLIO_URL}}` | `UserProfile.portfolioUrl()` | omit line |
| `{{GITHUB_URL}}` | `UserProfile.githubUrl()` | omit line |
| `{{LINKEDIN_URL}}` | `UserProfile.linkedinUrl()` | omit line |
| `{{SKILLS}}` | `UserProfile.skills()` joined with `", "` | omit line |
| `{{PROJECTS}}` | `UserProfile.projects()` rendered as `• name — description (techStack)` lines | omit block |

**Global rule:** a rendered email must never contain a raw `{{...}}` token. Unresolvable placeholders drop their whole line (or block, for `{{PROJECTS}}`). The same tokenized body doubles as the AI prompt's reference example (`EmailGenerationService`), so the template and the prompt can never drift apart.

---

## API

```java
public record TemplateResult(String subject, String body) {}

public TemplateResult generate(Job job, User user, UserProfile profile);
```

- Takes the `Job` (for title/company) plus the candidate's `User` and `UserProfile` (identity and contact resolution)
- Returns `TemplateResult` with the built subject and body
- Does **not** persist anything — caller (`EmailGenerationService`) owns persistence

---

## Scenarios

### Scenario 1: template generated for high-match job with a full profile
- **GIVEN** a `Job` with `title = "Desenvolvedor Java Júnior"` and `company = "Acme Corp"`, and a `User`/`UserProfile` with name, phone, URLs, skills and projects
- **WHEN** `generate(job, user, profile)` is called
- **THEN** returns a `TemplateResult` with:
  - `subject` containing both `"Desenvolvedor Java Júnior"` and `"Acme Corp"`
  - `body` containing both title and company, the resolved personal data, and no `{{...}}` token

### Scenario 2: missing optional fields omit their lines
- **GIVEN** a profile with `phone = null` and `githubUrl` blank
- **WHEN** `generate(job, user, profile)` is called
- **THEN** the phone/GitHub signature lines are absent and no `{{PHONE}}` / `{{GITHUB_URL}}` leaks into the body

### Scenario 3: template used as AI reference (unchanged)
- **GIVEN** an email generation request for a low-match job (`matchScore < threshold`)
- **WHEN** the AI prompt is built
- **THEN** the template email is included as a tokenized example to guide the model's output format and tone, followed by the `CANDIDATE FACTS` block with the resolved values