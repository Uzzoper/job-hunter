# Spec: Contact Email Extraction from Description

> **Layer:** `infrastructure`
> **Implementation file:** `com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer`
> **Corresponding test:** `JobNormalizerTest.java`
>
> **Self-match guard (owner email):**
> - `JobNormalizer` is the single choke point all providers flow through — contact emails
>   are only assigned there via `EmailExtractor.extract(...)`.
> - Owner emails come from `users.email` via the `UserRepository` port (new
>   `findAllEmails()`), loaded through the shared `OwnerEmailGuard` helper.
> - The **same** guard also covers `CompanySiteEnricher` output: an owner email crawled
>   from a company website is discarded before it can be attached (or cached).
> - Migration `V5__clear_self_contact_emails.sql` fixes existing rows.
> - **Corresponding tests:** `JobNormalizerTest` (`OwnerEmailSelfMatchGuard` cases),
>   `OwnerEmailGuardTest`, `CompanySiteEnricherTest` (`OwnerSelfMatch` cases)

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

### Scenario 9: extracted email matches a registered user's email (exact case)
- **GIVEN** a `RawJob` with description `"Send your resume to user@example.org"` and a
  registered user with email `user@example.org`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 10: extracted email matches a registered user's email (case variant)
- **GIVEN** a `RawJob` with description `"Send your resume to USER@Example.ORG"` and a
  registered user with email `user@example.org`
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `null`

### Scenario 11: extracted email is a distinct company email
- **GIVEN** a `RawJob` with description `"Send your resume to hiring@techcorp.com"` and a
  registered user set that does **not** contain that email
- **WHEN** `normalize(raw)` is called
- **THEN** the returned `Job` has `contactEmail` set to `"hiring@techcorp.com"`

---

## Business rules

1. Emails are extracted from both `title` and `description` using a regex pattern
2. Only the **first** valid email found is used (title searched first, then description)
3. Emails matching `noreply@`, `donotreply@`, `no-reply@`, `apply@` are considered non-contact and ignored
4. Emails with domains `example.com`, `exemplo.com`, `test.com`, `domain.com` are considered placeholders and ignored
5. If no valid email is found, `contactEmail` remains `null`
6. Extraction happens inside `JobNormalizer.normalize()` after all filters pass, just before constructing the `Job` record
7. **Owner self-match guard**: after extraction, if `lower(trim(contactEmail))` is in the set of
   `lower(trim(ownerEmails))` — where owner emails come from the `users` table via
   `UserRepository.findAllEmails()` — then `contactEmail` is set to `null` and a WARN is logged.
   This prevents the job owner's own email from being misattributed as a company contact
   (e.g. job #256 where the owner's personal email was attributed as the company contact),
   which breaks email idempotency and would send application emails back to the owner.
   The guard is applied by **every** stage that can set a contact email: description
   extraction in `JobNormalizer` and company-site enrichment in `CompanySiteEnricher`.

> **Multi-user semantic (explicit):** `jobs` is a **global** table — the owner self-match
> guard and the V5 data fix are **global by design**: they run for every job regardless of
> the requesting user. The current deployment is single-owner, and this was explicitly
> signed off; the implementation is generic (no user email hardcoded anywhere). A future
> multi-user deployment would require a per-user scoping redesign, which is explicitly out
> of scope today.

---

## Interface contract

The `UserRepository` port (application layer) gains one method:

```java
List<String> findAllEmails();
```

It is implemented by the persistence adapter using an email-only projection
(`select u.email from UserEntity u` — no full-entity fetch). The guard itself lives in the
shared infrastructure helper `OwnerEmailGuard(UserRepository)`: each `JobNormalizer` bean
in `AppConfig` builds its own from the `UserRepository` port, and the `companySiteEnricher`
bean receives the shared `OwnerEmailGuard` bean — so Gupy, InfoJobs and LinkedIn (plus the
company-site enrichment stage) are all protected by the same logic.

The user email set is fetched lazily on first use and cached per guard instance
(normalizers and the enricher are singletons). Registered users created
after the first fetch are picked up on the next application restart — an acceptable
operational trade-off to avoid a DB query per job (no over-engineering).

**DB-unreadable contract:** if `findAllEmails()` throws (e.g. database down), the guard is
**fail-closed per job**: the candidate contact email is discarded (never persisted
unverified) and no exception escapes to `normalize()`/`enrich()`. A WARN is logged **once**
(not per job); subsequent failures log at DEBUG. The owner set is **not** cached on failure,
so the load is **retried on the next call** (retry-while-down); as soon as the query
succeeds the normal behavior resumes.

---

## Data fix (migration)

`V5__clear_self_contact_emails.sql`:

```sql
-- V5: clear self-match contact emails — a registered user's own email must never be kept
-- as a job contact email (job #256).
-- trim() mirrors the guard's normalization (trim + lowercase) for parity.
UPDATE jobs SET contact_email = NULL
WHERE lower(trim(contact_email)) IN (SELECT lower(trim(email)) FROM users);
```

**Blast radius / sign-off (explicit):**
- This is a **retroactive, global** data fix: it nulls `contact_email` on **existing** rows
  in the global `jobs` table where the value equals a registered user's email
  (case-insensitive, trimmed).
- **Preview before running** (dry-run of what will be cleared):
  ```sql
  SELECT id, title, url, contact_email
  FROM jobs
  WHERE lower(trim(contact_email)) IN (SELECT lower(trim(email)) FROM users);
  ```
- **Backup** the database file before applying the migration:
  ```bash
  cp data/jobhunter.db data/jobhunter.db.bak
  ```
- **Sign-off — single-owner deployment:** the operator approved this change. This
  deployment has exactly **one** owner, the `jobs` table is global, and this fix (like the
  guard) is global by design — it is safe and intended in this deployment. A future
  multi-user deployment must re-review this migration (per-user scoping), which is out of
  scope today.

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| Invalid regex | N/A | Pattern is compiled at class load time, tested |
| Description with malformed email | N/A | Ignored by regex, treated as no match |
| `UserRepository` returns null entry | N/A | Null/blank entries filtered out when building the owner email set |
| `findAllEmails()` returns null (instead of a list) | N/A | Treated as an empty set (nil-guard); contact emails are kept |
| `findAllEmails()` throws (DB unreadable) | Never propagates | Fail-closed per job: contact email discarded (never persisted unverified); WARN logged once; owner set not cached, so the load retries on the next call (retry-while-down) |

---

## Out of scope

- Does not extract from structured API fields (Gupy, LinkedIn metadata)
- Does not validate that the email is deliverable (SMTP check)
- LinkedIn microservice changes are out of scope
- Per-user scoping of the owner self-match guard (a future multi-user deployment) — today
  the guard and the V5 data fix are intentionally global for the single-owner deployment
  (see "Multi-user semantic" above)

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/contact-email-extraction.md.

Step 1 — add tests to JobNormalizerTest covering all
scenarios in this spec. The tests must fail (RED).
Do not write the implementation yet.

Step 2 — wait for confirmation before implementing.
```
