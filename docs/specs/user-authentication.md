# Spec: User Authentication

> **Layer:** `web` | `application` | `domain` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.AuthController`
> - `com.juanperuzzo.job_hunter.application.service.AuthService`
> - `com.juanperuzzo.job_hunter.infrastructure.security.SecurityConfig`
> - `com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenFilter`
> - `com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenService`
> - `com.juanperuzzo.job_hunter.infrastructure.security.BotTokenFilter`
> - `src/main/resources/application.yaml` (`bot.service.*`)
> **Corresponding tests:** `AuthControllerTest.java`, `AuthServiceTest.java`, `JwtTokenServiceTest.java`, `AuthIntegrationTest.java`, `BotTokenFilterTest.java`

---

## Expected behavior

The application protects all job management, profile, and analysis resources. Only authenticated users can manage profiles, list jobs, analyze them, and draft emails.

Public routes: `POST /api/auth/register`, `POST /api/auth/login` only.

### Scenario 1: User Registration
- **GIVEN** a new guest visitor
- **WHEN** they submit their name, email, and password to `POST /api/auth/register`
- **THEN** the system hashes the password with BCrypt
- **AND** saves the user in the `users` table
- **AND** issues a JWT for the new user
- **AND** returns HTTP 201 Created with `token`, `userId`, `name`, and `email`.

### Scenario 2: Successful Login
- **GIVEN** a registered user
- **WHEN** they submit their email and password to `POST /api/auth/login`
- **THEN** the system validates credentials
- **AND** issues a stateless JWT
- **AND** returns HTTP 200 OK with `token`, `userId`, `name`, and `email`.

### Scenario 3: Failed Login (Bad Credentials)
- **GIVEN** a login request
- **WHEN** the password or email does not match
- **THEN** the system returns HTTP 401 Unauthorized with message `"Invalid email or password"`.

### Scenario 4: Requesting Protected Resource without Token
- **GIVEN** an unauthenticated request to `/api/jobs` (or any protected route)
- **WHEN** no `Authorization` header is present
- **THEN** the system returns HTTP 401 Unauthorized with body `{"error":"Unauthorized"}`.

---

## Business rules

- **Password hashing:** BCrypt via Spring `PasswordEncoder`, wrapped by the `PasswordHasher` port.
- **JWT lifespan:** Configurable via `jwt.expiration-hours` in `application.yaml` (default: 24).
- **JWT secret:** Required env var `JWT_SECRET` (minimum 32 characters for HMAC).
- **Token format:** `Authorization: Bearer <JWT_TOKEN>`.
- **Authentication principal:** After validation, the Spring Security context holds the domain `User` object (not a raw user id string).

---

## Interface contract

### HTTP — `AuthController`

| Method | Path | Auth | Response |
|--------|------|------|----------|
| POST | `/api/auth/register` | None | `201` + `AuthResponse` |
| POST | `/api/auth/login` | None | `200` + `AuthResponse` |

### DTOs

```java
// Web layer — shared request body (name optional on login)
public record AuthRequest(String name, String email, String password) {}

// Web layer — DTO exposed as JSON response
public record AuthResponse(String token, Long userId, String name, String email) {}
```

Example register response:

```json
{
  "token": "eyJ...",
  "userId": 1,
  "name": "Juan",
  "email": "juan@example.com"
}
```

### Ports

```java
public interface AuthUseCase {
    AuthResult register(String name, String email, String password);
    AuthResult login(String email, String password);
}
```

---

## Error cases

| Situation | Exception | HTTP |
|-----------|-----------|------|
| Registration with existing email | `EmailAlreadyExistsException` | 409 Conflict |
| Invalid credentials on login | `InvalidCredentialsException` | 401 Unauthorized |

---

## Database

Migration `V3__create_users_and_profiles_tables.sql` creates the `users` table (`id`, `email`, `name`, `password_hash`, `created_at`).

---

## Out of scope

- Password reset / forgot password
- Role-based access control (all users have the same permissions)
- `tokenType` field (Bearer is implied by the header format)
- Separate `RegisterResponse` vs `LoginResponse` DTOs (unified as `AuthResult`)

---

## Service token (issue #47)

> **Goal:** Let a self-hosted external client (the job-hunter bot) call the protected API using a
> long-lived service token instead of a user JWT, while keeping every request scoped to a single
> owner user so existing per-user behavior stays intact.
>
> **Layer:** `infrastructure/security` (filter) + `infrastructure/config` (properties) +
> `application.yaml` (config). No `domain` changes, no DB migration.
>
> **Corresponding tests:** `unit/web/BotTokenFilterTest.java`

### Context

The bot needs access to the API to list jobs and trigger actions, but the normal JWT flow requires an
interactive login and a short-lived, expiring token. A static, long-lived service token simplifies
the self-hosted bot deployment, but a single shared secret carries **no per-request identity**. To
keep every downstream request scoped to a concrete owner (the existing `CurrentUserService` /
`getCurrentUserId()` path), the owner must be resolved from configuration rather than from the token
itself.

### Owner-resolution decision (documented first)

A static `X-Bot-Token` cannot encode a userId, so the owner user is **resolved from configuration —
never hardcoded in code**. The deployer whitelists which existing user the bot acts as via
`bot.service.owner-user-id` (env-provided). This reuses the JWT path's principal mechanism: the bot
filter places a `User(id = ownerUserId, ...)` in the `SecurityContext`, so
`CurrentUserService.getCurrentUserId()` returns the owner id and every downstream per-user query
(from `JobController`/services/repositories) resolves exactly as it would for a JWT-authenticated
owner. This is intentionally minimal: no new owner-tracking table, no role model, no lookup query.

### Flow

1. Client sends header `X-Bot-Token: <token>`.
2. `BotTokenFilter` runs **before** `JwtTokenFilter`.
3. If `bot.service.api-key` is blank (feature disabled) → filter is **inert**, request falls through
   to the normal JWT chain (byte-identical behavior).
4. Else if `X-Bot-Token` exactly equals the configured `bot.service.api-key` **and**
   `bot.service.owner-user-id` resolves to a positive id → authenticate: set a `User` principal for
   the owner in the `SecurityContext` and continue. The JWT filter then runs but never
   overwrites an existing authentication — it only sets a principal when none is present,
   so the bot principal wins (see the precedence sentence below).
5. Else (token missing **or** mismatched) → do **not** authenticate; fall through to the normal JWT
   chain. Existing behavior is byte-identical (a request without a valid `Authorization: Bearer`
   header is rejected with `401` by the standard `authenticationEntryPoint`).

### Components

| Component | Responsibility |
|---|---|
| `BotTokenFilter` (`infrastructure/security`) | `OncePerRequestFilter`; reads `X-Bot-Token`, compares against configured key, sets the bot/owner principal on exact match; otherwise passes through |
| `SecurityConfig` (`infrastructure/security`) | Registers `BotTokenFilter` **before** `JwtTokenFilter` in the chain |
| `application.yaml` | `bot.service.api-key` (blank default = disabled) and `bot.service.owner-user-id` (blank default = unresolved) |

### Configuration

```yaml
bot:
  service:
    api-key: ${BOT_SERVICE_API_KEY:}          # blank = feature disabled
    owner-user-id: ${BOT_SERVICE_OWNER_USER_ID:}  # existing user the bot acts as
```

- **Feature flag:** `bot.service.api-key` blank/absent → disabled, filter inert.
- **No expiry:** service tokens are static. **Revocation = rotate the `api-key` value and restart**
  the application (documented behavior — do not rely on in-memory revocation caches).
- **Owner:** `bot.service.owner-user-id` must be set (positive) alongside `api-key` for the filter to
  authenticate; otherwise the filter falls through to the JWT chain (treats itself as unconfigured).

### Behaviour matrix (acceptance mapping)

| Scenario | Config | Request header | Result |
|---|---|---|---|
| Valid bot token | key set, owner set | `X-Bot-Token: <exact match>` | **200**, principal scoped to owner userId |
| Wrong bot token | key set | `X-Bot-Token: <wrong>` | Falls through to JWT chain → **401** (no JWT) |
| Missing bot token | key set | no `X-Bot-Token` | Falls through to JWT chain → **401** (no JWT) |
| Feature disabled | key blank | any `X-Bot-Token` | Filter inert → JWT chain → **401** (no JWT) |
| Valid bot token + JWT | key set, owner set | `X-Bot-Token: <match>` + `Authorization: Bearer <jwt>` | Bot principal wins: `BotTokenFilter` runs first and `JwtTokenFilter` guards on `SecurityContextHolder.getContext().getAuthentication() == null`, so a JWT never overwrites an already-set bot principal |

### Security properties

- **Precedence decision (documented):** a valid `X-Bot-Token` always wins over a `Bearer`
  JWT — `JwtTokenFilter` skips authentication when the `SecurityContext` already holds a
  principal, so the bot owner is never shadowed by a JWT user. This keeps every bot request
  scoped to the configured owner even if the bot client also forwards an `Authorization` header.
- **Constant-time comparison / exact match:** the filter compares the provided token against
  the configured key with `MessageDigest.isEqual` (constant-time, not a plain `equals`),
  protecting the self-hosted service token from timing side-channels.
- **No expiry:** rotation + restart is the revocation mechanism.
- **Per-user scoping:** the bot is never an anonymous superuser — every action runs under the
  owner's userId via the existing `CurrentUserService` path.

### Interface contract

Acceptance mapping rows map 1:1 to `BotTokenFilterTest` cases:

```
validToken_whenConfigured_shouldAuthenticateAsOwner()      → 200, owner-scoped
validBotTokenAndJwt_shouldPreserveBotPrincipal()           → 200, owner-scoped (bot wins over JWT)
invalidToken_whenConfigured_shouldFallThroughToJwt()       → 401 without JWT
missingToken_whenConfigured_shouldFallThroughToJwt()       → 401 without JWT
noKey_whenDisabled_shouldFilterInert()                     → 401 without JWT
```

### Out of scope

- Scoping the bot to more than one owner, roles, or per-route permissions.
- Token rotation endpoints / in-memory revocation caches.
- Changing `CurrentUserService` or any `domain` code.
- Touching `docs/bot-onboarding.md`, `README`, or `skills/`.
