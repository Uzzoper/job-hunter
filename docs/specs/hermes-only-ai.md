# Spec: Hermes as the only AI provider (issue #65)

> **Layer:** `infrastructure` (AI adapters + config wiring) + config/docs follow-up
> **Implementation files:** `com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient`
> (sole `AiPort` bean), `com.juanperuzzo.job_hunter.infrastructure.config.AppConfig`,
> `src/main/resources/application.yaml`, `docker-compose.yaml`
> **Deleted files:** `com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient`,
> `com.juanperuzzo.job_hunter.infrastructure.ai.OllamaClient`,
> `OpenRouterClientTest.java`, `OllamaClientTest.java`
> **Corresponding tests:** `HermesAgentClientTest.java` (kept),
> `AiPortWiringTest` (new), `HermesConfigFailFastTest` (new)
> **Depends on:** `hermes-agent-integration.md` (scenarios 4-9, error semantics kept);
> `AiPort` consumers (`analyze-job.md`, `generate-email.md`)
> **Supersedes:** the `ai.provider` switch (`openrouter`/`ollama`/`hermes`) and
> `retry-backoff-ai.md` (archived — no retry inside adapters)

---

## Context

The multi-provider setup actively misleads: an analyze failure made the operator
chase a missing `OPENROUTER_API_KEY` even though the stack runs on `hermes`.
The `openrouter`/`ollama` paths are dead config surface, code surface (clients +
`@ConditionalOnProperty` branches in `AppConfig`), and doc surface with zero
production use. Hermes is the project direction.

Decision (approved): remove the `ai.provider` switch and the non-Hermes
clients. The Hermes gateway becomes the single AI backend for analysis and
email generation. Decided open points:

- `hermes.model` reads `${HERMES_MODEL:default}` (new env var, defaults to the
  Bot profile pinned model). It serves both `HermesAgentClient` (analysis) and
  `HermesBotEmailSender` (email delegation) through the shared `hermes:` block.
- No retry inside adapters (matches Hermes spec §103: "no retry inside
  adapters"). Transient 429s fail loudly instead of retrying.
- Fail-fast: an unconditional `HermesAgentClient` bean plus mandatory
  `HERMES_API_KEY` means the Spring context does not start without the key —
  even for non-AI flows. This is intended per issue #65 ("keep failing loudly,
  no silent fallback").

---

## Expected behavior

### Wiring (`AppConfig`)

#### Scenario 1: unconditional Hermes bean

- **GIVEN** any configuration without `ai.provider`
- **WHEN** the Spring context starts (with `HERMES_API_KEY` set)
- **THEN** exactly one `AiPort` bean exists and it is a `HermesAgentClient`
- **AND** no `@ConditionalOnProperty(name = "ai.provider", ...)` remains on any
  AI bean

#### Scenario 2: missing key fails startup

- **GIVEN** `HERMES_API_KEY` is unset
- **WHEN** the Spring context starts
- **THEN** startup fails with an unresolved-placeholder error naming the key
- **AND** no fallback provider is wired silently

### Analysis + email generation

#### Scenario 3: analyze through Hermes with no provider env set

- **GIVEN** a reachable gateway and default config (no `ai.provider`,
  no `OPENROUTER_API_KEY`)
- **WHEN** `POST /api/jobs/{id}/analyze` runs
- **THEN** the completion goes through `HermesAgentClient` and returns normally

#### Scenario 4: email draft through Hermes with no provider env set

- **GIVEN** the same setup
- **WHEN** `POST /api/jobs/{id}/email` runs
- **THEN** the draft is generated through `HermesAgentClient` with no provider
  env set

#### Scenario 5: gateway failure stays loud

- **GIVEN** the gateway is unreachable, slow, or answers HTTP 4xx/5xx (or HTTP
  200 with embedded `finish_reason: "error"`)
- **WHEN** `complete(prompt)` is called
- **THEN** throws `AiException` (scenarios 5, 6, 9 of
  `hermes-agent-integration.md`, unchanged) — no retry, no fallback

---

## Business rules

- Single `AiPort` implementation: `HermesAgentClient`. `AiAnalysisService`,
  `EmailGenerationService`, `ResumeUploadService`, and `ResumeTailoringService`
  consume it through `AiPort` — untouched.
- One shared `hermes:` config block serves both adapters (analysis + email
  delegation) — same gateway, same credentials.
- No retry inside adapters. `retry-backoff-ai.md` is archived, not re-scoped.
- `HERMES_MODEL` defaults to `default` so existing Bot profiles keep working.

---

## Interface contract

No new ports — `AiPort` is unchanged:

```java
public interface AiPort {
    String complete(String prompt);
}
```

Configuration (`application.yaml`):

```yaml
ai:
  resume-extraction:
    max-chars: 8000
  resume-tailoring:
    max-chars: 8000

# Hermes Agent gateway ("hermes gateway run") — the single AI backend for
# analysis and email generation, and the email-sending bot transport.
hermes:
  base-url: http://localhost:9119/v1   # clients append /chat/completions — the /v1 suffix is mandatory (404 without it)
  api-key: ${HERMES_API_KEY}           # equals the profile's API_SERVER_KEY — required, context fails fast without it
  model: ${HERMES_MODEL:default}       # model pinned on the Bot profile
  timeout-seconds: 120
```

Deleted: `ai.provider`, `ai.ollama.*`, `ai.openrouter.*`.

Compose fresh-clone surface: only `HERMES_API_KEY` (plus optional
`HERMES_MODEL`) is required for AI. `OPENROUTER_API_KEY` and `AI_PROVIDER`
are removed.

---

## Files to change

| Area | Change |
|---|---|
| `AppConfig.java` | Delete `openRouterClient` + `ollamaClient` beans and their `@ConditionalOnProperty`; remove `@ConditionalOnProperty` from `hermesAgentClient`; drop orphan imports |
| `infrastructure/ai/` | Delete `OpenRouterClient.java`, `OllamaClient.java` |
| `application.yaml` | Delete `ai.provider` + `ollama`/`openrouter` blocks and their comments; reword stale OpenRouter/Ollama comments (`scraper.retry` note); `hermes.model` → `${HERMES_MODEL:default}` |
| `docker-compose.yaml` | Remove `OPENROUTER_API_KEY` + `AI_PROVIDER`; keep `HERMES_API_KEY`, add `HERMES_MODEL` |
| `vibeguard.config.json` | Drop `sk-or-v1-*` regex + `OPENROUTER_API_KEY` from secrets |
| Tests | Delete `OpenRouterClientTest`, `OllamaClientTest`; strip `OPENROUTER_API_KEY` registry entries from 4 integration tests; add `AiPortWiringTest` + `HermesConfigFailFastTest` |
| Docs | `AGENTS.md`, `README.md`, `docs/bot-onboarding.md`, `docs/specs/prompts.md`, touched specs (`architecture.md`, `hermes-agent-integration.md` scenario 10 + config block, `resume-upload.md`, `ats-resume-tailoring.md`, `send-email.md`, `user-scoped-analysis.md`, `sqlite-local-persistence.md`, `email-enrichment.md`); archive `retry-backoff-ai.md` |

Local-only (gitignored, not committed): clean the real OpenRouter key from
`.env` and `application-local.yaml`; switch the local file off `provider: ollama`.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| `HERMES_API_KEY` unset at startup | placeholder-resolution failure | context does not start (fail-fast, even for non-AI flows) |
| Gateway HTTP 4xx/5xx on analysis | `AiException` | propagates to AI use cases, no retry, no fallback |
| Gateway unreachable/timeout on analysis | `AiException` | cause carries the timeout; propagates |
| HTTP 200 with `finish_reason: "error"` | `AiException` | embedded upstream failure treated as completion failure |

---

## Verification

- `grep -ri "openrouter\|ollama" src/main` → empty
- `grep -ri "ai\.provider\|AI_PROVIDER" .` → empty (except gitignored `.env`/`.sisyphus`)
- `./mvnw test --batch-mode` green; `npm test` in `linkedin-scraper` unaffected
- Live smoke (manual): `HERMES_API_KEY=… HERMES_MODEL=…` → analyze + email
  draft through Hermes with no provider env set

---

## Out of scope

- Key rotation/secret management, per-stack prompt tailoring, new providers
- JWT/security, scraper config, non-AI flows (untouched, though they now
  require the key at startup per the fail-fast decision)
- Installing/configuring Hermes Agent itself (see `hermes-agent-integration.md`)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/hermes-only-ai.md.

Step 1 — write AiPortWiringTest (Spring context boots with ai.provider absent;
@Autowired AiPort is a HermesAgentClient) and HermesConfigFailFastTest
(context fails fast without HERMES_API_KEY, error naming the key).
Delete OpenRouterClientTest and OllamaClientTest.
Do not touch implementation files yet.

Step 2 — wait for confirmation before implementing.
```
