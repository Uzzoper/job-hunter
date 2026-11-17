# Email Send Flow — armadilhas e comportamento

> Reference para a skill `job-hunter-diagnostics`. Explica por que `approve` não envia e como diagnosticar.

---

## O fluxo de envio de emails

```
POST /api/jobs/{id}/analyze     → matchScore persistido
POST /api/jobs/{id}/email       → draft criado (status=PENDING)
POST /api/jobs/{id}/email/approve → status=APPROVED
                                   ↓
                          AutoSendScheduler (a cada 120s)
                                   ↓
                          Envio via Hermes/bot
```

---

## Armadilha: `auto-send.enabled: false`

```yaml
# application.yaml (local)
auto-send:
  enabled: false      # ← envio real DESATIVADO
  require-review: true
```

Quando `enabled: false`, o `AutoSendScheduler` **não roda**. O `POST /email/approve` muda o status para `APPROVED` mas o email nunca é despachado para o Hermes/bot. O `sentAt` permanece `null`.

**Sintoma:** `GET /api/jobs/{id}/email` retorna `status: APPROVED, sentAt: null` mesmo minutos depois.

**Diagnóstico:**
```bash
# Verificar se o scheduler está ativo nos logs
docker logs job-hunter-backend --since 5m | grep -i autosend
# (sem output = scheduler inativo)
```

**Fix:** mudar `auto-send.enabled: true` em `application.yaml` + restart do backend. Após restart, o scheduler roda a cada 120s e envia os APPROVED pendentes.

**Cuidado com `require-review: true`:** quando ativo, o scheduler só envia drafts que foram explicitamente aprovados (status=APPROVED). Drafts PENDING não são enviados automaticamente — isso é intencional para dar chance de revisão humana.

---

## Validação pós-envio

```bash
# Após approve + scheduler ativo, o status muda para SENT
curl -s -H "X-Bot-Token: $TOKEN" http://localhost:8080/api/jobs/{id}/email | python3 -c "
import json,sys
d = json.load(sys.stdin)
print(f\"status={d['status']} sentAt={d.get('sentAt')}\")
"
```

- `status=SENT, sentAt=2026-...` → enviado com sucesso
- `status=APPROVED, sentAt=null` → aprovado mas scheduler não rodou (verificar `enabled` e logs)

---

## Dedup de emails

O backend dedup por URL: se o job já existe no banco, o fetch retorna `saved=0`. Porém, se um email já foi gerado e aprovado para aquele job, o `POST /email` subsequente pode retornar conflito ou sobrescrever — depende da implementação. Verificar `EmailGenerationService` se houver comportamento inesperado.

## Generic template trap (score < 60)

Quando `matchScore < email.standard-template.min-match-score` (60), o `POST /api/jobs/{id}/email` retorna HTTP 200 com um **placeholder**:
- subject: `"Subject: [Aplicação externa]"`
- body: `"Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado."`
- status: `SENT`

O 200 e o `SENT` são campos do próprio stub — uma checagem só de status lê como sucesso.

**Sempre imprimir `subject` e `body` antes de reportar um draft como gerado.**
Se o usuário quiser email pra um job sub-60, redigir na mão (no idioma da vaga) e mostrar antes de enviar.

---
