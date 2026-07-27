# Spec: User Profile Management

> **Layer:** `web` | `application` | `domain` | `infrastructure`
> **Implementation files:**
> - `com.juanperuzzo.job_hunter.web.controller.ProfileController`
> - `com.juanperuzzo.job_hunter.application.service.UserProfileService`
> - `com.juanperuzzo.job_hunter.infrastructure.persistence.UserProfilePersistenceAdapter`
> **Corresponding tests:** `UserProfileServiceTest.java`, `ProfileControllerTest.java`

---

## Expected behavior

Each registered user has a unique profile containing resume text, target skills, communication tone, and a list of personal/academic/professional projects. Profiles are required before AI job analysis (see `user-scoped-analysis.md`).

### Scenario 1: Fetch Profile
- **GIVEN** an authenticated user
- **WHEN** they call `GET /api/profile`
- **THEN** returns saved `resumeText`, `skills`, `tone`, and `projects` (each with `name`, `description`, `techStack`).
- **AND** if no profile row exists yet, returns HTTP 200 OK with a blank template (`resumeText: ""`, `skills: []`, `tone: STARTUP`, `id: null`, `projects: []`).

### Scenario 2: Save/Update Profile
- **GIVEN** an authenticated user
- **WHEN** they submit resume, skills, tone, and projects to `PUT /api/profile`
- **THEN** the system creates or updates the row in `user_profiles` linked to the authenticated user id, and replaces all projects atomically (delete old, insert new).
- **AND** returns HTTP 200 OK with the updated profile including saved projects.

---

## Business rules

- **CV content:** `resumeText` must be at least 50 characters on save (`PUT`).
- **Skills list:** Array of target technologies (e.g. `["Java", "Spring Boot", "PostgreSQL"]`).
- **Projects list:** Array of `Project` objects, each with `name` (required), `description` (required), `techStack` (array of strings).
- **Communication tone:** One of `CompanyTone`: `FORMAL`, `CASUAL`, `STARTUP` (JSON enum name).
- **User resolution:** `ProfileController` reads `userId` from the authenticated `User` principal in the security context.
- **Project persistence:** Projects are stored in `user_projects` table, linked by `user_id`. On save, old projects are deleted and new ones inserted (atomic replacement).

---

## Interface contract

### HTTP — `ProfileController`

| Method | Path | Auth | Response |
|--------|------|------|----------|
| GET | `/api/profile` | Bearer | `200` + `ProfileResponse` |
| PUT | `/api/profile` | Bearer | `200` + `ProfileResponse` |

### DTOs

```java
public record ProjectRequest(String name, String description, List<String> techStack) {}
public record ProjectResponse(String name, String description, List<String> techStack) {}

public record ProfileRequest(
    String resumeText,
    List<String> skills,
    CompanyTone tone,
    List<ProjectRequest> projects
) {}

public record ProfileResponse(
    Long id,
    Long userId,
    String resumeText,
    List<String> skills,
    CompanyTone tone,
    List<ProjectResponse> projects
) {}
```

### Application service

`UserProfileService` is injected directly into `ProfileController` (no separate `ManageProfileUseCase` port).

```java
public class UserProfileService {
    UserProfile getProfile(Long userId);
    UserProfile saveProfile(Long userId, String resumeText, List<String> skills, CompanyTone tone, List<Project> projects);
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
Migration `V5__add_user_projects_table.sql` creates `user_projects` (`user_id` FK to `users`, `name`, `description`, `tech_stack`).

---

## Out of scope

- Profile photo or file upload (resume is plain text only)
- Public profile visibility between users
