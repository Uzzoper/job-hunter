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

## Worked example (fictional persona — Desenvolvedor Full Stack Júnior, React/TS/Supabase)

> Illustrative only: the candidate, the project and every contact value below
> are fictional. Identity fields are filled at draft time from the bot's own
> profile/memory; the narrative beats and the step order are the real ones.

```
Assunto: Dev Full Stack Junior

Olá,

Meu nome é Ana Souza, sou desenvolvedora full stack júnior e construo o painel de controle da Atesta, uma startup de logística. A vaga chamou atenção porque é o tipo de produto que desenvolvo no dia a dia: SaaS com front e back integrados.

Na Atesta, atuo como desenvolvedora única — do front em React/Next.js ao back em Node.js, com APIs REST, autenticação, webhooks e integrações com APIs de IA. É também onde sustento PostgreSQL e Prisma em produção. Foi aí que desenvolvi a parte que a vaga mais pede: organização para evoluir código existente sem quebrar o que já funciona.

Sou honesta sobre onde estou: sou júnior. Minha base sólida é PostgreSQL e SQL — que se transfere direto para o Supabase, já que é o mesmo Postgres por baixo. Em Tailwind, Supabase e em Prisma em profundidade minha experiência é mais rasa: uso Prisma em um SaaS menor do que o de vocês, e Tailwind/Supabase foram em projetos pontuais. Subo rápido em stack nova — com n8n, que ainda não usei, já estudei e automatizei fluxos por conta própria.

Meu inglês é C1, o que facilita documentação e as APIs de IA que vocês usam.

Podem me conhecer melhor em anasouza.dev e github.com/anasouza-dev.

Atenciosamente,
Ana Souza
ana.souza.dev@gmail.com
(11) 90000-0000
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
