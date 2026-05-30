# Spec: User Authentication

> **Layer:** `web` | `application` | `domain` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.AuthController`
> - `com.juanperuzzo.job_hunter.web.config.SecurityConfig`
> - `com.juanperuzzo.job_hunter.web.config.JwtTokenFilter`
> **Corresponding tests:** `AuthControllerTest.java`, `JwtTokenFilterTest.java`

---

## Expected behavior

The application must protect all job management and analysis resources. Only authenticated users can register their profiles, list jobs, analyze them, and draft emails.

### Scenario 1: User Registration
- **GIVEN** a new guest visitor
- **WHEN** they submit their name, email, and password to `POST /api/auth/register`
- **THEN** the system encrypts the password
- **AND** saves the user in the database
- **AND** returns HTTP 201 Created containing user ID, name, and email.

### Scenario 2: Successful Login
- **GIVEN** a registered user
- **WHEN** they submit their email and password to `POST /api/auth/login`
- **THEN** the system validates credentials
- **AND** issues a stateless JSON Web Token (JWT) with user details
- **AND** returns HTTP 200 OK with the token.

### Scenario 3: Failed Login (Bad Credentials)
- **GIVEN** a login request
- **WHEN** the password or email does not match
- **THEN** the system returns HTTP 401 Unauthorized with message "Invalid email or password".

### Scenario 4: Requesting Protected Resource without Token
- **GIVEN** an unauthenticated request to `/api/jobs`
- **WHEN** no Authorization header is present
- **THEN** the system returns HTTP 401 Unauthorized.

---

## Business rules

- **Password Hashing:** Passwords must be hashed using a strong hashing algorithm (e.g. BCrypt) before being stored. No plain-text passwords allowed in the database.
- **JWT Lifespan:** The issued JWT token should expire after 24 hours.
- **Token Format:** The token must be sent in the header as: `Authorization: Bearer <JWT_TOKEN>`.

---

## Interface contract

```java
// AuthController DTOs
public record RegisterRequest(String name, String email, String password) {}
public record RegisterResponse(Long id, String name, String email) {}
public record LoginRequest(String email, String password) {}
public record LoginResponse(String token, String tokenType) {}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Registration with existing email | `EmailAlreadyExistsException` | Return HTTP 409 Conflict |
| Invalid credentials on login | `BadCredentialsException` | Return HTTP 401 Unauthorized |

---

## Out of scope

- Password reset via email token links (forgot password).
- Role-based access control (all registered users have the same permissions).
