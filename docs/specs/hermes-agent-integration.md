# Spec: Hermes Agent integration (email sending + AI analysis)

> **Layer:** `infrastructure` (two adapters + config wiring)
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.email.HermesBotEmailSender`, `com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient`
> **Corresponding test:** `HermesBotEmailSenderTest.java`, `HermesAgentClientTest.java`
> **Depends on:** `send-email.md` (`EmailSenderPort` contract); `AiPort` consumers (`analyze-job.md`, `generate-email.md`)
> **Supersedes:** the Resend-backed adapter referenced in `send-email.md` (removed entirely — single-user pre-release)

---

## Context

The application emails are no longer sent through an email API (Resend). Instead they are delegated to a **Hermes Agent** bot (Nous Research): a named profile running behind the headless gateway (`hermes gateway run`, typically installed as a systemd user service), which exposes an OpenAI-compatible API (`POST /v1/chat/completions`, default port 9119, bearer-key auth). The same gateway can also serve as the AI analysis provider (`ai.provider=hermes`), alongside `openrouter` and `ollama`.

Key property: **this repo contains no email-delivery logic**. The Java side hands the bot a structured send instruction; the bot performs delivery with whatever email tool is configured in its own profile (in the validated setup: the himalaya CLI v2, driven by a standing instruction in the profile's `SOUL.md`). Analysis likewise reuses the standard chat-completions shape already spoken by `OpenRouterClient`/`OllamaClient`.

### External prerequisites (not code)

1. Hermes Agent installed; a dedicated Bot profile created (`hermes profile create jobhunter-bot --clone-all`) whose **email tool** is the himalaya CLI v2 (`~/.local/bin/himalaya` — adjust to your `$HOME`), configured via `~/.config/himalaya/config.toml` (password resolved by shell command straight from the profile `.env`, never duplicated). The send protocol lives as a standing instruction in the profile's `SOUL.md`: send with `himalaya message compose --send --attach resume.pdf` and reply only `EMAIL_SENT` (success) or `EMAIL_TOOL_MISSING` (tool/config missing)
2. Gateway running: `hermes gateway run` — or persisted as a systemd user service (`jobhunter-bot gateway install` + linger) — serving `http://localhost:9119/v1` with strong `API_SERVER_KEY` and `API_SERVER_PORT=9119` set in the profile `.env` (the key auto-enables the platform `api_server`)
3. `HERMES_API_KEY` env var set to that key
4. `approvals.mode: off` in the bot profile's `config.yaml` (interactive approvals would stall the request until timeout)
5. **Resume attachment** (single-user setup): the user's PDF lives inside the Bot profile
   (`~/.hermes/profiles/<bot>/resume.pdf`) and a `SOUL.md` standing instruction tells the Bot
   to attach it on every Job Hunter application email. The backend sends no file and knows
   nothing about it — re-uploading a resume in the app requires re-copying the file manually.
   Multi-user would need per-send staging through `EmailSenderPort` instead (deliberately deferred).

---

## Expected behavior

### HermesBotEmailSender (implements `EmailSenderPort`)

#### Scenario 1: successful delegation
- **GIVEN** a reachable Hermes gateway and valid credentials
- **WHEN** `send(from, to, subject, body)` is called
- **THEN** a single `POST /chat/completions` is made with header `Authorization: Bearer <key>` and body `{model, stream:false, messages:[{role:"user", content:<instruction>}]}`
- **AND** the instruction text contains all four fields (from, to, subject, body) verbatim plus the order to use the bot's email tool unmodified
- **AND** on HTTP 2xx with a non-empty reply, no exception is thrown and the reply content is logged

#### Scenario 2: gateway returns HTTP 4xx/5xx
- **GIVEN** the gateway rejects the request (bad key, server error)
- **WHEN** `send(...)` is called
- **THEN** throws `EmailDeliveryException` (draft stays `PENDING`; `EmailSendingService` propagates it without re-wrapping)

#### Scenario 3: gateway unreachable / slow
- **GIVEN** the gateway is down or does not answer within `hermes.timeout-seconds`
- **WHEN** `send(...)` is called
- **THEN** throws `EmailDeliveryException` with a timeout-related cause

### HermesAgentClient (implements `AiPort`, mirrors `OllamaClient`)

#### Scenario 4: successful completion
- **GIVEN** a reachable gateway
- **WHEN** `complete(prompt)` is called
- **THEN** returns `choices[0].message.content` from the response

#### Scenario 5: gateway returns HTTP 4xx/5xx
- **WHEN** the completion call fails at HTTP level
- **THEN** throws `AiException`

#### Scenario 6: gateway unreachable / slow
- **WHEN** the call times out
- **THEN** throws `AiException`

#### Scenario 7: prompt with special characters
- **GIVEN** a prompt containing newlines, tabs, quotes and backslashes
- **WHEN** `complete(prompt)` is called
- **THEN** the request body is valid JSON carrying the prompt intact

### Embedded gateway errors (HTTP 200)

Observed against the real gateway: when the upstream model provider is saturated,
it may answer a **well-formed chat-completions body carrying `finish_reason: "error"`**
instead of an HTTP error status. A bare 2xx must not be trusted as success.

#### Scenario 8: gateway answers HTTP 200 with embedded error (sender)
- **GIVEN** a response with `choices[0].finish_reason == "error"`
- **WHEN** `send(...)` is called
- **THEN** throws `EmailDeliveryException` carrying the response content
- **AND** the reply is not logged as a successful acknowledgement

#### Scenario 9: gateway answers HTTP 200 with embedded error (analysis client)
- **GIVEN** the same gateway behavior
- **WHEN** `complete(prompt)` is called
- **THEN** throws `AiException` carrying the response content

### Wiring (`AppConfig`)

#### Scenario 10: provider selection
- **GIVEN** `ai.provider=hermes` in configuration
- **WHEN** the Spring context starts
- **THEN** a `HermesAgentClient` bean backs `AiPort` (same mechanism as `openrouter`/`ollama`)
- **AND** the `hermesBotEmailSender` bean backs `EmailSenderPort` regardless of the chosen AI provider (independent ports)
- **AND** both beans read the shared top-level `hermes:` config block

---

## Business rules

- No retry policy inside these adapters — matches `OllamaClient`; `scraper.retry.*` governs AI calls where applicable
- Success criterion for sending is HTTP 2xx + non-empty parseable reply **without an embedded error** (`finish_reason != "error"`) — structured metadata is enforced, free-form content is not. The `EMAIL_SENT` / `EMAIL_TOOL_MISSING` markers are conventions in the instruction text, logged when present but **not** enforced: matching free-form LLM replies would be brittle. If the bot lacks an email tool, the gateway still answers 2xx — check the logs
- One shared `hermes:` config block (`base-url`, `api-key`, `model`, `timeout-seconds`) serves both adapters — same gateway, same credentials
- Timeout default is 120 s (vs Resend's 15 s): the bot may run tools before replying
- `"stream": false` is sent explicitly; if a future gateway ignores it and answers SSE-only, parsing must be adjusted then

---

## Interface contract

No new ports — both adapters implement existing ones:

```java
// Output port — unchanged (see send-email.md)
public interface EmailSenderPort {
    void send(String from, String to, String subject, String body);
}

// Output port — unchanged
public interface AiPort {
    String complete(String prompt);
}
```

Configuration:

```yaml
hermes:
  base-url: http://localhost:9119/v1  # clients append /chat/completions — the /v1 suffix is mandatory (404 without it)
  api-key: ${HERMES_API_KEY}          # equals the profile's API_SERVER_KEY
  model: default                      # model pinned on the Bot profile
  timeout-seconds: 120
```

```yaml
ai:
  provider: openrouter   # "openrouter" (default) | "ollama" | "hermes"
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Gateway HTTP 4xx/5xx on send | `EmailDeliveryException` | draft stays `PENDING`; `EmailSendingService` passes it through unwrapped |
| Gateway unreachable/timeout on send | `EmailDeliveryException` | cause carries the timeout; draft stays `PENDING` |
| Upstream provider saturated → gateway answers HTTP 200 with `finish_reason: "error"` on send | `EmailDeliveryException` | embedded upstream failure treated as delivery failure, not as acknowledgement; draft stays `PENDING` |
| Gateway HTTP 4xx/5xx on analysis | `AiException` | propagates to AI use cases like other providers |
| Gateway unreachable/timeout on analysis | `AiException` | propagates |
| Upstream provider saturated → HTTP 200 with `finish_reason: "error"` on analysis | `AiException` | embedded upstream failure treated as completion failure |
| Bot has no email tool configured | none (2xx) | visible only via logged reply — the `EMAIL_SENT` / `EMAIL_TOOL_MISSING` markers are log conventions, **not** enforcement; actual delivery is verified out of band |

---

## Operations

- Gateway logs: `journalctl --user -u hermes-gateway-jobhunter-bot -f`, or the
  profile log files at `~/.hermes/profiles/jobhunter-bot/logs/{gateway,agent}.log`
  (adjust to your `$HOME`)
- Sent-mail check (out of band, on the gateway host):
  `himalaya envelope list -a <account> -m "[Gmail]/E-mails enviados"`
- Key rotation: change `API_SERVER_KEY` in the profile `.env` **and** the
  backend's `HERMES_API_KEY` together — the two must always match

---

## Out of scope

- Installing or configuring Hermes Agent, creating the Bot profile, or wiring its email tool (himalaya CLI) — external prerequisite documented in README
- The async `/v1/runs` lifecycle (approvals, steering, event streaming) — a possible future hardening if fire-and-forget chat completions prove unreliable
- Verifying actual inbox delivery from the backend — out of band
- Form-based application agents (see `send-email.md` out-of-scope notes)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/hermes-agent-integration.md.

Step 1 — write HermesAgentClientTest and HermesBotEmailSenderTest
(WireMock, same pattern as OllamaClientTest). Cover all scenarios.
Do not touch implementation files yet.

Step 2 — wait for confirmation before implementing.
```
