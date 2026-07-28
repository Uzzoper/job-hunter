# Spec: Template email for high-match jobs

> **Layer:** `application` (service)  
> **Implementation file:** `com.juanperuzzo.job_hunter.application.service.TemplateEmailService`  
> **Corresponding test:** `TemplateEmailServiceTest.java`  
> **Depends on:** `auto-send-scheduler.md` (triggered when `matchScore >= 60`)

---

## Context

Jobs with `matchScore >= 60` do not need AI-personalized emails. Instead, a fixed template with the candidate's personal introduction and portfolio is used. This saves AI credits and sends faster.

---

## Template

### Subject

```
Candidatura — {JOB_TITLE}
```

### Body (Portuguese)

```
Olá. Tudo bem?

Gostaria de me candidatar à vaga de {JOB_TITLE}.

Atualmente curso Engenharia de Software e venho me especializando em desenvolvimento back-end com Java. Tenho experiência prática com Java, Spring Boot, APIs REST, Git, bancos de dados relacionais e desenvolvimento de aplicações web.

Alguns destaques do meu portfólio:

• Job Hunter — API desenvolvida com Spring Boot, Clean Architecture, TDD e integração com Inteligência Artificial.
• LovLink (lovlink.com.br) — SaaS comercial em produção, banco de dados PostgreSQL, integração de pagamentos via Mercado Pago e arquitetura full stack moderna.
• Jishuu (jishuu.vercel.app) — plataforma com autenticação OAuth 2.0 (Google), gerenciamento de usuários e persistência de dados utilizando PostgreSQL.

Além dos requisitos da vaga, possuo conhecimentos em JavaScript, React, Node.js, Docker, testes automatizados e versionamento com Git. Estou sempre buscando aprimorar minhas habilidades e aprender novas tecnologias para contribuir cada vez mais com o time e com os projetos em que atuo.

Segue meu currículo em anexo. Fico à disposição para uma conversa.

Atenciosamente,

Juan Antonio Peruzzo
(42) 99833-1363
Portfólio: https://juanperuzzo.is-a.dev
GitHub: https://github.com/Uzzoper
```

**Placeholder:** `{JOB_TITLE}` is the only variable — replaced with the job title from `EligibleDraft.jobTitle()`.

---

## AI prompt update

The same template is included in the AI email prompt (Prompt 2 in `prompts.md`) as an **example** so that AI-generated emails for low-match jobs follow the same structure, tone, and level of detail.

---

## Model changes

`EligibleDraft` gains a new field:

| Field | Type | Source |
|---|---|---|
| `jobTitle` | `String` | `Job.title()` — set in `AutoSendEligibilityService` |

This 5th parameter is appended to the existing constructor; existing callers must be updated.

---

## Scenarios

### Scenario 1: template generated for high-match job
- **GIVEN** an `EligibleDraft` with `matchScore >= 60` and a `jobTitle`
- **WHEN** `generate(eligible)` is called
- **THEN** an `EmailDraft` is created with the template subject (containing the job title) and body (containing the job title, portfolio links, and signature)
- **AND** the draft is saved with `status = PENDING`

### Scenario 2: template used as AI reference
- **GIVEN** an email generation request for a low-match job (`matchScore < 60`)
- **WHEN** the AI prompt is built
- **THEN** the template email is included as an example to guide the model's output format and tone
