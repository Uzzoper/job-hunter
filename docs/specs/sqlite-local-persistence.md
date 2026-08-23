# Spec: SQLite as the only database (local-first)

> **Layer:** `infrastructure` (persistence + config) — `domain` and `application` remain untouched
> **Implementation files:** `pom.xml`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/**`, `src/main/java/com/juanperuzzo/job_hunter/infrastructure/persistence/*Entity.java`, new `StringListConverter`, `docker-compose.yaml`, `.github/workflows/ci.yml`, `README.md`, `AGENTS.md`
> **Corresponding tests:** `StringListConverterTest.java`, integration tests booting Flyway + adapters against SQLite

---

## Goal

PostgreSQL is removed from the project. **SQLite is the only database** — runtime, tests, and CI.
The backend runs with zero infrastructure: clone → add API keys → `java -jar`. No Docker required
for the database; Docker Compose keeps only the LinkedIn scraper container.

---

## Expected behavior

### Scenario 1: fresh clone boots without Docker
- **GIVEN** a fresh clone, no Docker running, no `DB_*` env vars set
- **WHEN** the application starts
- **THEN** Flyway creates `./data/jobhunter.db` and applies the consolidated baseline schema
- **AND** `POST /api/auth/register` works end-to-end against the file database

### Scenario 2: string-list columns round-trip
- **GIVEN** a user profile with `skills = ["Java", "Spring"]` (and job analyses with `matched_skills` / `missing_skills`)
- **WHEN** the entity is saved and reloaded through the persistence adapter
- **THEN** the string list is identical after the round trip
- **AND** the column stores JSON text (`["Java","Spring"]`)

### Scenario 3: SQLite runs in WAL mode
- **GIVEN** the default SQLite datasource
- **WHEN** a connection is opened
- **THEN** `journal_mode` is `WAL` (concurrent reads during scraper/scheduler writes)

### Scenario 4: CI is hermetic
- **GIVEN** a pull request is opened
- **WHEN** the Maven CI job runs
- **THEN** it passes with **no service containers** (no Postgres)

### Scenario 5: compose keeps only the scraper
- **GIVEN** `docker compose up -d`
- **WHEN** the stack comes up
- **THEN** only `linkedin-scraper` starts (no `postgres` service, no DB volume)
- **AND** the backend (when run in compose) mounts `./data` for the SQLite file instead of Postgres env vars

---

## Business rules

- **Rule 1 — SQLite everywhere.** Default and only datasource:
  `jdbc:sqlite:./data/jobhunter.db` (path overridable via `DB_URL`), driver `org.sqlite.JDBC`,
  no username/password. `DB_USER`/`DB_PASSWORD` are no longer required or read.
- **Rule 2 — one consolidated baseline migration.** `db/migration/V1..V8` (Postgres dialect) are
  replaced by a single `V1__baseline_schema.sql` in SQLite dialect carrying the final schema
  (`jobs`, `email_drafts`, `users`, `user_profiles`, `job_analyses`, `user_projects`, contact
  fields, `source`, `sent_at`). Consolidation is safe per AGENTS.md — these migrations only ever
  ran on local machines. Future schema changes are new `V2+` files in SQLite dialect.
- **Rule 3 — one entity mapping.** `String[]` list fields (`UserProfileEntity.skills`,
  `JobAnalysisEntity.matchedSkills/missingSkills`) drop `columnDefinition = "TEXT[]"` and use a
  shared JPA `AttributeConverter` serializing to/from JSON text. No dialect branching anywhere.
- **Rule 4 — SQLite dialect details.** `INTEGER PRIMARY KEY AUTOINCREMENT` for ids,
  `CURRENT_TIMESTAMP` instead of `NOW()`, constraints inline in `CREATE TABLE` (no
  `ALTER TABLE ... ADD CONSTRAINT`), WAL enabled.
- **Rule 5 — no secrets in code.** `JWT_SECRET`, `OPENROUTER_API_KEY`, `RESEND_API_KEY` remain
  env-var driven. SQLite introduces no credentials.
- **Rule 6 — Clean Architecture holds.** Only `infrastructure` (entities, converter, config, pom)
  and resources change. `domain` and `application` are untouched; repository ports keep signatures.

---

## Interface contract (port)

No port changes. Adapters keep `String[]` in/out. New infrastructure-only class:

```java
// infrastructure/persistence/StringListConverter.java
@Converter
public class StringListConverter implements AttributeConverter<String[], String> {
    // String[] <-> JSON text (e.g. "[\"Java\",\"Spring\"]"); null-safe both ways
}
```

---

## Dependencies (pom.xml)

- **remove** `org.postgresql:postgresql` and `org.flywaydb:flyway-database-postgresql`
- **add** `org.xerial:sqlite-jdbc` (runtime)
- **add** `org.hibernate.orm:hibernate-community-dialects` (runtime — `SQLiteDialect`)
- **add** the Flyway SQLite support artifact required by the Flyway version managed by Boot
  (`org.flywaydb:flyway-community-db-support`)

---

## Error cases

| Situation | Exception thrown | Expected behavior |
|---|---|---|
| SQLite file corrupt / not a database | Flyway / SQLite exception | Startup fails fast with clear log; deleting the file resets the DB (documented) |
| `data/` dir not writable | startup failure | Clear error pointing at `DB_URL` override |
| Converter receives null | — | Returns null (nullable columns); NOT NULL columns guarded by schema |

---

## Out of scope

- Does not include migrating existing local PostgreSQL data (fresh start; a one-time export script can be a future spec)
- Does not handle making `JWT_SECRET` / `OPENROUTER_API_KEY` / `RESEND_API_KEY` optional (future "optional integrations" spec)
- Does not include SaaS/multi-instance concerns (if ever needed, ports keep a future Postgres re-adoption cheap)
- Does not change the CLI (its rusqlite cache is already SQLite) or the LinkedIn scraper service

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/sqlite-local-persistence.md.

Step 1 — write StringListConverterTest (RED) and the integration test
booting Flyway + persistence adapters against a temp SQLite file (RED).
Do not write production code yet.

Step 2 — wait for confirmation before implementing.
```
