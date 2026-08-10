# Spec: GitHub Actions CI/CD

> **Layer:** `config` (CI/CD workflows, no runtime code)
> **Implementation files:** `.github/workflows/ci.yml`, `.github/workflows/code-quality.yml`, `.github/workflows/cli-release.yml`
> **Related docs:** `AGENTS.md` (commit convention table gains a `ci` type)

---

## Expected behavior

### Scenario 1: CI runs on every push to `main`/`dev` and on every PR
- **GIVEN** a push to `main` or `dev`, or a pull request against any branch
- **WHEN** the `CI` workflow triggers
- **THEN** three jobs run in parallel: Backend (Maven), CLI (Rust), Scraper (Node.js)
- **AND** `concurrency.cancel-in-progress: true` cancels superseded runs for the same ref

### Scenario 2: backend job passes in a fresh checkout
- **GIVEN** a clean checkout with no local Postgres instance
- **WHEN** `./mvnw test --batch-mode` runs
- **THEN** the `integration/AuthIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`) connects to a Postgres 16 service container mapped to `localhost:5433`
- **AND** the service is created with `POSTGRES_DB=jobhunter`, `POSTGRES_USER` and `POSTGRES_PASSWORD` matching the test's `DynamicPropertySource` values (`your_db_user` / `your_db_password`)
- **AND** the job exports `DB_URL`, `DB_USER`, `DB_PASSWORD` so the context can boot (no defaults exist in `application.yaml`)
- **AND** the unit test suites (JUnit 5 + Mockito + WireMock) still run without a Spring context

### Scenario 3: code-quality runs only checks that actually exist
- **GIVEN** the repo's `pom.xml` has no `fmt-maven-plugin` and no `maven-checkstyle-plugin`
- **WHEN** the `Code Quality` workflow triggers
- **THEN** the backend job is **not present** (no silent no-op job)
- **AND** the CLI job runs `cargo clippy -- -D warnings` and the Scraper job runs `npx tsc --noEmit`

### Scenario 4: CLI release on version tags
- **GIVEN** a push of a tag matching `v*`
- **WHEN** the `CLI Release` workflow triggers
- **THEN** the release binary is built for `x86_64-unknown-linux-gnu`, `aarch64-apple-darwin` and `x86_64-pc-windows-msvc` from `cli/Cargo.toml` (package name `jh-cli`)
- **AND** artifacts are uploaded to the GitHub Release named after the tag; if the release does not exist yet, it is created with generated notes

### Scenario 5: README badges
- **GIVEN** the repository at `Uzzoper/job-hunter`
- **WHEN** the README badge row is rendered
- **THEN** badges point to `ci.yml` (label "CI") and `cli-release.yml` (label "CLI Release"), which match the workflow `name:` fields

---

## Business rules

- Java 21 (Temurin) via `actions/setup-java@v4` with Maven cache — never bare `mvn`, only `./mvnw`
- Node.js 22 with npm cache keyed on `linkedin-scraper/package-lock.json`
- Rust via `dtolnay/rust-toolchain@stable`; `Swatinem/rust-cache@v2` scoped to the `cli` workspace
- Postgres service image matches `docker-compose.yaml` (`postgres:16-alpine`), port mapping `5433:5432` on `localhost`
- The `Code Quality` backend job is only reintroduced together with actual formatter/checkstyle plugins in `pom.xml`
- All YAML files end with a trailing newline
- CI/quality workflow commits use the `ci` commit type (added to `AGENTS.md`)

---

## Error cases

| Situation | Expected behavior |
|---|---|
| Postgres service not reachable at `localhost:5433` | Backend job fails on context boot — service is mandatory for `./mvnw test` |
| `AuthIntegrationTest` credentials drift from service env | Backend job fails with auth error — service user/password must match `DynamicPropertySource` (`your_db_user` / `your_db_password`) |
| Rust lint error | CLI job fails via `-- -D warnings` |
| TypeScript compile error | Scraper job fails via `tsc --noEmit` |
| Release already exists at tag | `gh release upload --clobber` overwrites assets; `gh release create` fallback only on missing release |

---

## Out of scope

- Adding `fmt-maven-plugin` / `maven-checkstyle-plugin` (or spotless/checkstyle config) to `pom.xml` — the no-op backend job is removed instead; reintroducing checks requires a separate spec and a formatted codebase
- Extracting shared checkout/setup steps into a composite action (3 workflows, duplication is acceptable)
- Badge for the `Code Quality` workflow
- Backend release/deployment workflow, Docker image publishing, and the `linkedin-scraper` release pipeline
- JWT/API secrets handling beyond what the test already overrides via `DynamicPropertySource`

---

## Agent prompt (OpenCode)

```
Read the spec at docs/specs/github-actions-ci.md.

Fix the three workflows to match this spec:
1. ci.yml — add the Postgres 16 service and DB_* job env vars; ensure trailing newline.
2. code-quality.yml — remove the no-op backend job; ensure trailing newline.
3. cli-release.yml — keep as-is (already compliant).
Also add the `ci` type to the commit convention table in AGENTS.md.
Do not modify pom.xml. Verify with a YAML parser afterward.
```
