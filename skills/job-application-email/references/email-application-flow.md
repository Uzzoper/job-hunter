# Email application flow — template + worked example

## When this channel applies

- The listing has a real `contactEmail` — a recruiting/company address, not a
  placeholder like `accommodations@`, `candidate_accessibility@`, `more@`.
- The job is dev/júnior/remote and matches the target profile.
- No portal apply flow is available, or the user chose email.

## Draft structure (PT-BR, concise, non-repetitive)

1. **Opening** — name + role + one line on why THIS role (the product/stack).
2. **Match** — the concrete overlap (stack, product, what you own end-to-end).
3. **Gaps** — name the shallow skills honestly; pair each with the
   transferable base + ramp-up speed. Never inflate seniority.
4. **Logistics** — English level, remote, portfolio/GitHub.
5. **Sign-off** — name, email, phone.

Standing style rules: gender-neutral (no `(a)` markers), no redundant
phrases, PT-BR, year-only dates.

## Subject line

- Use the role the posting asks for (e.g. the post says "Assunto: Dev Full
  Stack Junior" → subject `Dev Full Stack Junior`).
- Keep the subject OUT of the body.

## Worked example (fictional persona — junior full stack, preferred real-world format)

> Illustrative only: the candidate, the project and every contact value below
> are fictional. Identity fields are filled at draft time from the bot's own
> profile/memory; the FORMAT (greeting, paragraph order, signature block) and
> the beats are the real ones.

```
Assunto: Dev Full Stack Junior

Prezados,

Meu nome é Alex Souza, sou Desenvolvedor Full Stack em Maringá/PR e graduando em Engenharia de Software. Vi a oportunidade de Desenvolvedor Full Stack com foco em IA generativa — Júnior e gostaria de apresentar minha candidatura.

Tenho experiência prática com desenvolvimento web — TypeScript, React, Node.js e SQL — com base principal em Java/Spring Boot. Em IA generativa, minha vivência ainda é de estudo autodidata com Python e LLMs/APIs de IA: é a área que escolhi para crescer, e já integrei APIs de IA no meu projeto pessoal — o Atesta (painelatesta.com.br), um SaaS próprio em produção desde 2025, com assinaturas pagas e e-mails transacionais.

Na seção de projetos e diferenciais do meu portfólio, deixo meu trabalho com agentes de IA e automação de fluxos: https://alexsouza.is-a.dev — meu currículo também está em anexo para avaliação.

Estou aberto a aprender e crescer em uma área nova como a de IA generativa, e meu perfil combina com a forma autônoma e prática que a vaga descreve. Agradeço pela oportunidade e fico à disposição para conversar.

Atenciosamente,

Alex Souza
Desenvolvedor Full Stack — Maringá/PR
(44) 90000-0000
alex.souza.dev@gmail.com

GitHub: github.com/alexsouza-dev
Portfólio: alexsouza.is-a.dev
LinkedIn: linkedin.com/in/alex-souza-dev
```

## Send + record

The resume is downloaded fresh at send time (no standing local copy — the
backend profile is the source of truth):

```bash
TOKEN=$(cat <bot-profile-dir>/api-token.txt)

curl -s -o /tmp/resume_send.pdf -w 'http=%{http_code} type=%{content_type}\n' \
  -H "X-Bot-Token: $TOKEN" \
  http://127.0.0.1:8080/api/profile/resume
# gate: HTTP 200 / application/pdf / %PDF-  -> any failure: ABORT, reply EMAIL_TOOL_MISSING

~/.local/bin/himalaya message compose -a dev --send \
  -t <to> -s "<subject>" \
  --body-file /tmp/email_<id>_body.txt \
  --attach /tmp/resume_send.pdf

rm -f /tmp/resume_send.pdf
```

- Reply `EMAIL_SENT` on success; `EMAIL_TOOL_MISSING` if config/resume absent.
- Record: applied-state (job id + company + email) and a `§ jobs-hunt` line.
- Dedupe: same recipient + same vacancy = one send, ever.
