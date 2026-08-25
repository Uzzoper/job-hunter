# Spec: Template email for high-match jobs

> **Layer:** `application` (service)  
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.TemplateEmailService`  
> **Corresponding test:** `TemplateEmailServiceTest.java`  
> **Depends on:** `generate-email.md` (called by `EmailGenerationService` when `matchScore >= threshold`)

---

## Context

Jobs with `matchScore >= minMatchScore` (default: 60) do not need AI-personalized emails. Instead, a fixed template with the candidate's personal introduction and portfolio is used. This saves AI credits and sends faster.

`TemplateEmailService` is a stateless builder — it returns a `TemplateResult` record with subject and body. Persistence is owned by `EmailGenerationService`, which calls this service and saves the draft through the same upsert path used by the AI branch.

---

## Template

### Subject

```
Candidatura — {JOB_TITLE} na {COMPANY}
```

### Body (Portuguese)

```
Olá. Tudo bem?

Gostaria de me candidatar à vaga de {JOB_TITLE} na {COMPANY}.

Sou desenvolvedor back-end focado no ecossistema Java/Spring, com projetos em produção construídos com Java, Spring Boot, APIs REST, Git e bancos de dados relacionais.

Alguns destaques do meu portfólio:

• Job Hunter — API desenvolvida com Spring Boot, Clean Architecture, TDD e integração com Inteligência Artificial.
• LovLink (lovlink.com.br) — SaaS comercial em produção, banco de dados PostgreSQL, integração de pagamentos via Mercado Pago e arquitetura full stack moderna.
• Jishuu (jishuu.vercel.app) — plataforma com autenticação OAuth 2.0 (Google), gerenciamento de usuários e persistência de dados utilizando PostgreSQL.

Além dos requisitos da vaga, trabalho também com JavaScript, React, Node.js, Docker e testes automatizados. Posso demonstrar qualquer um desses projetos em funcionamento em uma conversa rápida.

Segue meu currículo em anexo. Podemos agendar uma conversa para eu mostrar esses projetos rodando?

Atenciosamente,

Juan Antonio Peruzzo
(42) 99833-1363
Portfólio: https://juanperuzzo.is-a.dev
GitHub: https://github.com/Uzzoper
```

**Placeholders:** `{JOB_TITLE}` and `{COMPANY}` — both are substituted from `Job.title()` and `Job.company()`.

---

## API

```java
public record TemplateResult(String subject, String body) {}

public TemplateResult generate(Job job);
```

- Takes a `Job` (not `EligibleDraft` — the service has no dependency on scheduler-only types)
- Returns `TemplateResult` with the built subject and body
- Does **not** persist anything — caller (`EmailGenerationService`) owns persistence

---

## Scenarios

### Scenario 1: template generated for high-match job
- **GIVEN** a `Job` with `title = "Desenvolvedor Java Júnior"` and `company = "Acme Corp"`
- **WHEN** `generate(job)` is called
- **THEN** returns a `TemplateResult` with:
  - `subject` containing both `"Desenvolvedor Java Júnior"` and `"Acme Corp"`
  - `body` containing both `"Desenvolvedor Java Júnior"` and `"Acme Corp"`, portfolio links, and signature

### Scenario 2: template used as AI reference (unchanged)
- **GIVEN** an email generation request for a low-match job (`matchScore < threshold`)
- **WHEN** the AI prompt is built
- **THEN** the template email is included as an example to guide the model's output format and tone
