# Spec: User Profile Management

> **Layer:** `web` | `application` | `domain`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.ProfileController`
> - `com.juanperuzzo.job_hunter.application.port.in.ManageProfileUseCase`
> - `com.juanperuzzo.job_hunter.application.service.UserProfileService`
> **Corresponding tests:** `UserProfileServiceTest.java`, `ProfileControllerTest.java`

---

## Expected behavior

Each registered user has a unique profile containing their resume (curriculum), target skills, and email communication tone.

### Scenario 1: Fetch Profile
- **GIVEN** an authenticated user
- **WHEN** they call `GET /api/profile`
- **THEN** the system returns their saved resume text, list of key skills, and tone preference.
- **AND** if no profile is registered yet, it returns a blank template with HTTP 200 OK.

### Scenario 2: Save/Update Profile
- **GIVEN** an authenticated user
- **WHEN** they submit their resume, target skills, and tone to `PUT /api/profile`
- **THEN** the system creates or updates the profile in the database linked to the authenticated user ID
- **AND** returns HTTP 200 OK with the updated profile details.

---

## Business rules

- **CV Content:** The resume text cannot be empty (must be at least 50 characters to allow a meaningful AI match).
- **Hardskills List:** The user should submit a list of target technologies (e.g. `["Java", "Spring", "PostgreSQL", "Angular"]`).
- **Communication Tone:** Must match one of the predefined values of `CompanyTone` (`FORMAL`, `CASUAL`, `STARTUP`).

---

## Interface contract

```java
// Profile Controller DTOs
public record ProfileRequest(String resumeText, List<String> skills, CompanyTone tone) {}
public record ProfileResponse(Long id, Long userId, String resumeText, List<String> skills, CompanyTone tone) {}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Resume text too short | `IllegalArgumentException` | Return HTTP 400 Bad Request |
| Unsupported tone value | `HttpMessageNotReadableException` | Return HTTP 400 Bad Request |
| User not found | `UserNotFoundException` | Return HTTP 404 Not Found |
