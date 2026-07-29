# Spec: Contact Email Extraction from Description

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer`
> **Corresponding test:** `JobNormalizerTest.java`

---

## Expected behavior

### Scenario 1: description contains a valid email
- **GIVEN** a `RawJob` with a description containing `"Send your resume to hiring@techcorp.com"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `"hiring@techcorp.com"`

### Scenario 2: description contains multiple emails
- **GIVEN** a `RawJob` with description `"Contact joao@empresa.com or rh@empresa.com"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `"joao@empresa.com"` (first match)

### Scenario 3: description has no email
- **GIVEN** a `RawJob` with description `"Apply through our website"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 4: email in title (unusual but possible)
- **GIVEN** a `RawJob` with title `"Developer job — contact@startup.io"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `"contact@startup.io"`

### Scenario 5: no-reply or donotreply email
- **GIVEN** a `RawJob` with description `"Do not reply — noreply@company.com"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 6: placeholder/example email
- **GIVEN** a `RawJob` with description `"Email us at exemplo@exemplo.com"`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 7: description is null
- **GIVEN** a `RawJob` with `description = null`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 8: job already excluded (no email extraction attempt)
- **GIVEN** a `RawJob` with a blank title
- **WHEN** `normalize(raw)` is called
- **THEN** the method returns `null` (no email extraction is attempted)

---

## Business rules

1. Emails are extracted from both `title` and `description` using a regex pattern
2. Only the **first** valid email found is used (title searched first, then description)
3. Emails matching `noreply@`, `donotreply@`, `no-reply@`, `apply@` are considered non-contact and ignored
4. Emails with domains `example.com`, `exemplo.com`, `test.com`, `domain.com` are considered placeholders and ignored
5. If no valid email is found, `contactEmail` remains `null`
6. Extraction happens inside `JobNormalizer.normalize()` after all filters pass, just before constructing the `Job` record

---

## Interface contract

No new ports. The `NormalizerPort` interface already returns `Job` which has the `contactEmail` field.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Invalid regex | N/A | Pattern is compiled at class load time, tested |
| Description with malformed email | N/A | Ignored by regex, treated as no match |

---

## Out of scope

- Does not extract from structured API fields (Gupy, LinkedIn metadata)
- Does not validate that the email is deliverable (SMTP check)
- Does not update existing jobs in the database retroactively
- LinkedIn microservice changes are out of scope

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/contact-email-extraction.md.

Step 1 — add tests to JobNormalizerTest covering all
scenarios in this spec. The tests must fail (RED).
Do not write the implementation yet.

Step 2 — wait for confirmation before implementing.
```
