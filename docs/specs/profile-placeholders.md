# Spec: Profile placeholders in application emails

> **Layer:** `application` (service + new resolver)
> **Implementation files:** `com.juanperuzzo.job_hunter.application.service.ProfilePlaceholders` (new), `TemplateEmailService`, `EmailGenerationService`
> **Corresponding tests:** `ProfilePlaceholdersTest.java` (new), `TemplateEmailServiceTest.java`, `EmailGenerationServiceTest.java`
> **Depends on:** `template-email.md`, `generate-email.md`, `user-profile.md` (profile is the data source), `prompts.md` (prompt carries the same facts block)

---

## Context

The email template and the AI prompt reference example hardcode one person's data (name, phone, portfolio, GitHub, project blurbs). Anyone else cloning the project must edit source code to use it. The profile already stores all of this (`UserProfile`: phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl, skills, projects; `User`: name, email — see `user-profile.md`).

This spec replaces hardcoded personal data with `{{PLACEHOLDERS}}` resolved from (`User`, `UserProfile`) at generation time. Syntax follows the existing `ResumeTailoringService` convention (`{{FULL_NAME}}`, `{{CONTACT}}`, …): double braces, `UPPER_SNAKE_CASE`.

---

## Placeholders

| Placeholder | Source | Missing-data rule |
|---|---|---|
| `{{JOB_TITLE}}`, `{{COMPANY}}` | `Job.title()`, `Job.company()` (unchanged behavior) | n/a (always present) |
| `{{CANDIDATE_NAME}}` | `User.name()` | omit line (`User.name` is `@NotBlank` at registration, so always present in practice) |
| `{{CANDIDATE_EMAIL}}` | `User.email()` | omit line |
| `{{PHONE}}` | `UserProfile.phone()` | omit line |
| `{{PORTFOLIO_URL}}` | `UserProfile.portfolioUrl()` | omit line |
| `{{GITHUB_URL}}` | `UserProfile.githubUrl()` | omit line |
| `{{LINKEDIN_URL}}` | `UserProfile.linkedinUrl()` | omit line |
| `{{SKILLS}}` | `UserProfile.skills()` joined with `", "` | omit line |
| `{{PROJECTS}}` | `UserProfile.projects()` rendered as `• name — description (techStack)` lines | omit block |

**Global rule:** a rendered email must never contain a raw `{{...}}` token. Unresolvable placeholders drop their whole line (or block, for `{{PROJECTS}}`).

---

## API

```java
public record ResolvedPlaceholders(Map<String, String> values) {}

public final class ProfilePlaceholders {
    // Pure function — no repositories, no Spring.
    public static String resolve(String template, User user, UserProfile profile);
    public static ResolvedPlaceholders resolve(User user, UserProfile profile);
    public static String factsBlock(User user, UserProfile profile);
    public static String factsBlock(ResolvedPlaceholders placeholders);
}
```

- `TemplateEmailService.generate(Job job, User user, UserProfile profile)` — signature gains user + profile context; body uses `{{...}}` tokens resolved via `ProfilePlaceholders`.
- `EmailGenerationService.generate(userId, jobId)` — additionally loads `User` via `UserRepository.findById` (profile loading already exists); the AI prompt carries a `CANDIDATE FACTS` block built from the same resolver output, and the reference example uses `{{...}}` tokens instead of hardcoded personal data. Single source of truth — template and prompt can never drift again.
- No repository is injected into the controller; all resolution lives in the application layer.

---

## Scenarios

### Scenario 1: full profile resolves everything
- **GIVEN** a `User` with name/email and a `UserProfile` with phone, urls, skills, projects
- **WHEN** `generate(...)` is called
- **THEN** subject/body contain the resolved values and no `{{...}}` token remains

### Scenario 2: missing optional fields omit lines
- **GIVEN** a profile with `phone = null` and `githubUrl = blank`
- **WHEN** `generate(...)` is called
- **THEN** the phone/signature lines are absent and no `{{PHONE}}` / `{{GITHUB_URL}}` leaks into the output

### Scenario 3: missing profile fails loudly
- **GIVEN** `UserProfileRepository.findByUserId` returns empty
- **WHEN** `generate(...)` is called
- **THEN** throws a domain exception (`ProfileNotFoundException`) — personalization without a profile is an error, not a silent generic email

### Scenario 4: prompt facts match template (no drift)
- **GIVEN** any user + profile
- **WHEN** the AI prompt is built
- **THEN** its `CANDIDATE FACTS` block equals the resolver output for the same inputs (asserted by capturing the prompt in tests)

---

## Out of scope

- Resume-parsing changes (extraction already fills profile fields; name comes from registration).
- Per-stack templates (rejected — see ADR note: static templates can't tailor; the AI path owns tailoring).
- Prompt wording/tone tuning (that's `prompts.md` versioning, separate change).
- Backfilling already-persisted drafts (review gate forbids regeneration; fix applies to new drafts).

---

## Docs sync (same feature)

- `template-email.md`: body example uses `{{...}}` tokens + placeholder table.
- `prompts.md`: reference example uses `{{...}}` tokens + `CANDIDATE FACTS` block documented.
