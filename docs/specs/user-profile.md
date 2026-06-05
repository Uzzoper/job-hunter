# Spec: User Profile Management

> **Layer:** `web` | `application` | `domain` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.ProfileController`
> - `com.juanperuzzo.job_hunter.application.service.UserProfileService`
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.UserProfilePersistenceAdapter`
> **Corresponding tests:** `UserProfileServiceTest.java`, `ProfileControllerTest.java`

---

## Expected behavior

Each registered user has a unique profile containing resume text, target skills, and email communication tone. Profiles are required before AI job analysis (see `user-scoped-analysis.md`).

### Scenario 1: Fetch Profile
- **GIVEN** an authenticated user
- **WHEN** they call `GET /api/profile`
- **THEN** if a profile row exists in `user_profiles`, returns saved `resumeText`, `skills`, and `tone`.
- **AND** if no profile row exists yet, returns HTTP 200 OK with a blank template (`resumeText: ""`, `skills: []`, `tone: STARTUP`, `id: null`).

### Scenario 2: Save/Update Profile
- **GIVEN** an authenticated user
- **WHEN** they submit resume, skills, and tone to `PUT /api/profile`
- **THEN** the system creates or updates the row in `user_profiles` linked to the authenticated user id
- **AND** returns HTTP 200 OK with the updated profile.

---

## Business rules

- **CV content:** `resumeText` must be at least 50 characters on save (`PUT`).
- **Skills list:** Array of target technologies (e.g. `["Java", "Spring Boot", "PostgreSQL"]`).
- **Communication tone:** One of `CompanyTone`: `FORMAL`, `CASUAL`, `STARTUP` (JSON enum name).
- **User resolution:** `ProfileController` reads `userId` from the authenticated `User` principal in the security context.

---

## Interface contract

### HTTP — `ProfileController`

| Method | Path | Auth | Response |
|--------|------|------|----------|
| GET | `/api/profile` | Bearer | `200` + `ProfileResponse` |
| PUT | `/api/profile` | Bearer | `200` + `ProfileResponse` |

### DTOs

```java
public record ProfileRequest(String resumeText, List<String> skills, CompanyTone tone) {}

public record ProfileResponse(
    Long id,
    Long userId,
    String resumeText,
    List<String> skills,
    CompanyTone tone
) {}
```

### Application service

`UserProfileService` is injected directly into `ProfileController` (no separate `ManageProfileUseCase` port).

```java
public class UserProfileService {
    UserProfile getProfile(Long userId);
    UserProfile saveProfile(Long userId, String resumeText, List<String> skills, CompanyTone tone);
}
```

---

## Error cases

| Situation | Exception | HTTP |
|-----------|-----------|------|
| Resume text too short on save | `IllegalArgumentException` | 400 Bad Request |
| Invalid JSON / unsupported tone | `HttpMessageNotReadableException` | 400 Bad Request |
| Authenticated user id not found in DB | `UserNotFoundException` | 404 Not Found |

**Note:** `ProfileNotConfiguredException` is **not** thrown by `getProfile`. An empty template is returned instead. That exception is thrown by `AiAnalysisService` when `POST /api/jobs/{id}/analyze` is called without a saved profile row.

---

## Database

Migration `V3__create_users_and_profiles_tables.sql` creates `user_profiles` (`user_id` FK to `users`, `resume_text`, `skills`, `tone`).

---

## Out of scope

- Profile photo or file upload (resume is plain text only)
- Public profile visibility between users
