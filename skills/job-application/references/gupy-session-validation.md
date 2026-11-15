# Gupy Session & Application Validation — Production Notes

## URL Integrity Test (DECISIVE — produções e leituras truncadas)

O terminal pode truncar URLs longas com `...` por limite de coluna (o
`read_file` do MCP mostra o conteúdo integral do arquivo — por isso a URL vai
para um arquivo). Isso NÃO significa que a URL está corrompida no banco.

**Teste decisivo:**
```sql
SELECT id, url, length(url), url LIKE '%...%' as has_dots
FROM jobs WHERE id = <job_id>;
```

- Se `has_dots = 0`: URL íntegra. O `...` era só display. Ponto final, não reabrir.
- Se `has_dots = 1`: URL truncada no banco — investigar origem no scraper.

Aplicar o mesmo teste para qualquer campo que pareça suspeito em outputs do terminal.

## Candidaturas Incompletas (progresso 0 de N)

Quando um email chega dizendo que a candidatura não foi finalizada:

1. Causa mais comum: sessão do Gupy expirou antes de finalizar, ou Chromium crashou com a aba aberta.
2. Solução:
   - Fazer re-login no Chromium
   - Acessar `https://portal.gupy.io/my/applications`
   - Clicar no botão **"Ver andamento"** (não a URL `/candidates/applications/<id>` que redireciona para home)
3. Se a vaga foi removida do portal: marcar como `EXPIRED` e seguir.

## Screening Answers Padrão (via bot memory)

Sempre consultar `memories/MEMORY.md` para screening answers — os valores
declarados vivem na memória do bot (runtime da VM) e nunca são commitados
neste repositório (público).

Se um valor não está na memória, perguntar ao usuário — nunca inventar.
