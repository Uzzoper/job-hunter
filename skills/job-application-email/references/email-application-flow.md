# Email application flow: template and example

## When this channel applies

- The listing has a real `contactEmail`: a recruiting or company address, not a
  placeholder like `accommodations@`, `candidate_accessibility@`, `more@`.
- The job is dev/júnior/remote and matches the target profile.
- No portal apply flow is available, or the user chose email.

## Draft structure (PT-BR, concise, non-repetitive)

1. **Opening**: name, role, and one sentence about what interests the candidate in this role.
2. **Match**: concrete overlap in skills, product, or end-to-end ownership.
3. **Gaps**: state limited experience honestly and connect it to transferable
   skills and ability to learn. Never inflate seniority.
4. **Logistics**: English level, remote availability, portfolio, or GitHub.
5. **Sign-off**: name, email, and phone.

Use natural, direct Brazilian Portuguese. Keep the message specific to the role
and the candidate's experience. Avoid canned openings, repeated claims, inflated
skills, and em dashes. Use gender-neutral wording without `(a)` markers. Write
dates with the year only.

## Subject line

- Use the role the posting asks for (e.g. the post says "Assunto: Dev Full
  Stack Junior" → subject `Dev Full Stack Junior`).
- Keep the subject OUT of the body.

## Example (fictional junior full stack candidate)

> The candidate, project, and contact details below are fictional. At draft
> time, use the identity details from the bot's profile or memory. Follow the
> example's greeting, paragraph order, and signature format, but write the
> content for the actual role and candidate.

```
Assunto: Dev Full Stack Junior

Prezados,

Meu nome é Alex Souza. Sou da área de desenvolvimento full stack, em Maringá, PR, e sou graduando em Engenharia de Software. Estou me candidatando à vaga de Desenvolvedor Full Stack Júnior com foco em IA generativa. O produto e a oportunidade de trabalhar com essa tecnologia chamaram minha atenção.

Tenho experiência prática com TypeScript, React, Node.js e SQL, além de uma base sólida em Java e Spring Boot. Meu contato com IA generativa ainda é principalmente de estudo, com Python, LLMs e APIs. Também integrei APIs de IA ao Atesta (painelatesta.com.br), meu SaaS, que está em produção desde 2025 e tem assinaturas pagas e e-mails transacionais.

Meu portfólio, https://alexsouza.is-a.dev, inclui projetos com agentes de IA e automação. Também envio meu currículo em anexo.

Quero aprofundar meus conhecimentos em IA generativa e contribuir com a experiência que já tenho em desenvolvimento. Obrigado por considerar minha candidatura. Fico à disposição para conversar.

Atenciosamente,

Alex Souza
Desenvolvedor Full Stack, Maringá/PR
(44) 90000-0000
alex.souza.dev@gmail.com

GitHub: github.com/alexsouza-dev
Portfólio: alexsouza.is-a.dev
LinkedIn: linkedin.com/in/alex-souza-dev
```

## Send + record

Download the resume from the backend profile when sending. Do not keep a local
copy between applications:

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
