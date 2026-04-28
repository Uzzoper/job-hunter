# Spec: Job deduplication

> **Layer:** `domain`
> **Implementation file:** `com.juanperuzzo.job_hunter.domain.model.Job`
> **Corresponding test:** `JobTest.java`

---

## Expected behavior

### Scenario 1: two jobs with identical URLs
- **GIVEN** two `Job` instances with the same URL
- **WHEN** compared via `equals()`
- **THEN** they are considered equal

### Scenario 2: two jobs with different URLs
- **GIVEN** two `Job` instances with distinct URLs
- **WHEN** compared via `equals()`
- **THEN** they are considered different

### Scenario 3: job within expiration window
- **GIVEN** a job with `postedAt` 20 days ago
- **WHEN** `isExpired()` is called
- **THEN** returns `false`

### Scenario 4: expired job
- **GIVEN** a job with `postedAt` 31 days ago
- **WHEN** `isExpired()` is called
- **THEN** returns `true`

### Scenario 5: job exactly at the limit
- **GIVEN** a job with `postedAt` exactly 30 days ago
- **WHEN** `isExpired()` is called
- **THEN** returns `false` (30 days is still valid, 31+ expires)

---

## Business rules

- A `Job`'s identity is determined exclusively by its URL
- The expiration window is 30 days from `postedAt`
- `Job` is an immutable record — no setters, no mutable state
- `matchScore` is `Optional<Integer>` — absent until AI analysis

---

## Interface contract (port)

```java
// Pure domain — no Spring or JPA annotations
public record Job(
    Long id,
    String title,
    String company,
    String url,
    String description,
    LocalDate postedAt,
    Optional<Integer> matchScore
) {
    public boolean isExpired() { ... }

    // equals and hashCode based solely on url
    // id is null until persisted — never use it for equality
}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Null URL | `NullPointerException` (constructor) | Fails fast on creation |
| Null `postedAt` | `NullPointerException` (constructor) | Fails fast on creation |

---

## Out of scope

- Does not validate URL format
- Does not persist — that is the infrastructure layer's responsibility

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/deduplicate-jobs.md.

Step 1 — write JobTest at
src/test/java/.../domain/JobTest.java
covering all scenarios in this spec.
Use only plain JUnit 5 — no Mockito, no Spring.
The test must fail (RED).

Step 2 — after my confirmation, implement the Job record
with equals/hashCode based on url and the isExpired() method.
```
