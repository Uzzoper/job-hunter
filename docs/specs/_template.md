# Spec: [Feature Name]

> **Layer:** `domain` | `application` | `infrastructure` | `web`
> **Implementation file:** `com.juanperuzzo.job_hunter.[layer].[ClassName]`
> **Corresponding test:** `[ClassNameTest].java`

---

## Expected behavior

> Describe each scenario using Given/When/Then. One block per scenario.

### Scenario 1: [happy path scenario name]
- **GIVEN** [context / initial state]
- **WHEN** [action executed]
- **THEN** [expected result]
- **AND** [additional condition, if any]

### Scenario 2: [error scenario name]
- **GIVEN** [context]
- **WHEN** [action that causes error]
- **THEN** [expected behavior on error]

---

## Business rules

- Rule 1 — objective description
- Rule 2 — objective description

---

## Interface contract (port)

> Fill in only if this feature involves creating or using a port (interface).

```java
// Input port (use case)
public interface [UseCaseName] {
    [ReturnType] [methodName]([Params]);
}

// Output port (repository / client)
public interface [PortName] {
    [ReturnType] [methodName]([Params]);
}
```

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| [situation] | `[ExceptionClass]` | [what should happen] |

---

## Out of scope

> What this spec deliberately does NOT cover (avoids scope creep).

- Does not include [X]
- Does not handle [Y]

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/[name].md.

Step 1 — write the [ClassNameTest] covering all
scenarios in this spec. The test must fail (RED).
Do not write the implementation yet.

Step 2 — wait for confirmation before implementing.
```
