# Spec: User Authentication

> **Layer:** `web` | `application` | `domain` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.AuthController`
> - `com.juanperuzzo.job_hunter.application.service.AuthService`
> - `com.juanperuzzo.job_hunter.infrastructure.security.SecurityConfig`
> - `com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenFilter`
> - `com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenService`
> **Corresponding tests:** `AuthControllerTest.java`, `AuthServiceTest.java`, `JwtTokenServiceTest.java`, `AuthIntegrationTest.java`

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
