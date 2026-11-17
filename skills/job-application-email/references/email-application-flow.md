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

## Worked example (Desenvolvedor Full Stack Júnior, R$ 3.500, React/TS/Supabase)

```
Assunto: Dev Full Stack Junior

Olá,

Meu nome é {{USER_NAME}}, sou desenvolvedor full stack júnior e fundador do LovLink, um SaaS próprio em produção desde julho de 2025. A vaga chamou atenção porque é o tipo de produto que construo no dia a dia: SaaS com front e back integrados.

No LovLink, atuo como desenvolvedor único — do front em React/Next.js ao back em Node.js, com APIs REST, autenticação, webhooks e integrações com APIs de IA. É também onde sustento PostgreSQL e Prisma em produção. Foi aí que desenvolvi a parte que a vaga mais pede: organização para evoluir código existente sem quebrar o que já funciona.

Sou honesto sobre onde estou: sou júnior. Minha base sólida é PostgreSQL e SQL — que se transfere direto para o Supabase, já que é o mesmo Postgres por baixo. Em Tailwind, Supabase e em Prisma em profundidade minha experiência é mais rasa: uso Prisma no LovLink em escala menor do que um SaaS grande, e Tailwind/Supabase foram em projetos pontuais. Subo rápido em stack nova — com n8n, que ainda não usei, já estudei e automatizei fluxos por conta própria.

Meu inglês é C1, o que facilita documentação e as APIs de IA que vocês usam.

Podem me conhecer melhor em {{PERSONAL_SITE}} e {{GITHUB_URL}}.

Atenciosamente,
{{USER_NAME}}
{{EMAIL}}
{{PHONE}}
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
