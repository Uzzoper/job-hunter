# Screening Answers & Skip Conditions (Gupy forms)

User-corrected on 2026-09-18 and validated across a 30+ job batch.

## Standard Screening Answers

When the portal asks screening questions, use these declared values:

| Question | Answer |
|---|---|
| Angular experience? | **Sim** (2 projetos próprios, aprende rápido — não é stack principal mas tem base prática) |
| Ensino superior completo? | **Não** (cursando, previsão 2027) |
| Pretensão salarial | Aberto a negociação, conforme mercado para profissionais júnior |
| Inglês | C1 intermediário |

These are NOT invented — they come from bot memory (user-corrected during this
session). Do not improvise other values.

## Skip Conditions (do NOT submit, move on)

### ELIGIBILITY_BLOCK
The job requires "superior completo" + "N anos de experience" as KNOCKOUT
criteria. Juan does not meet these. Examples:
- Cresol: "Formado em Análise de Sistemas..." + "5+ anos como Desenvolvedor"
- Shift: "Experiência em desenvolvimento e manutenção de aplicações web" + screening marked superior completo as eliminatório

Mark `ELIGIBILITY_BLOCK` and skip — the portal would reject at the end and
send a rejection email anyway.

### DADOS_PESSOAIS
The screening form requires personal data NOT in the profile and that we must
not invent:
- RG
- Órgão/Estado de emissor do RG
- Nome da mãe
- Nome do pai
- Naturalidade (cidade/estado de nascimento)

Example: PagBank screening (9 questions including all of the above).

Mark `DADOS_PESSOAIS` and skip. Do not ask the user for these — just move on.

### NOT_AVAILABLE
The job listing returns 404 on the company's Gupy page, or the job title does
not appear in the public listing even after searching. The listing may have
expired or been removed.

Mark `NOT_AVAILABLE` and skip.

### LinkedIn (out of scope)
The `job_url` points to `linkedIn.com/jobs/view/...`. LinkedIn applications
are done **manually** by the user — there is no LinkedIn portal in the allow-list.

Mark `LINKEDIN_SKIP` and skip.

## Pular próxima vaga

When any of the above conditions is hit, move to the next job in the batch
immediately. Do not stop the batch for a single blocked job. Report the skip
concisely (one line per job) and continue.
