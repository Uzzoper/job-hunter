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
- **GIVEN** a clean checkout with no local database and no `DB_*` env vars
- **WHEN** `./mvnw test --batch-mode` runs
- **THEN** the `integration/AuthIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`) connects to a temp-file SQLite datasource (no service containers, no Docker)
- **AND** the datasource is overridden via `DynamicPropertySource` (`jdbc:sqlite:<tempfile>`, driver `org.sqlite.JDBC`) and Flyway applies the consolidated baseline migration
- **AND** the job requires no database env vars — `application.yaml` defaults to the local SQLite file
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
- **AND** the upload step sets `GH_REPO: ${{ github.repository }}` — the `release` job has no checkout, so `gh` cannot resolve the repository from git remotes

### Scenario 5: README badges
- **GIVEN** the repository at `Uzzoper/job-hunter`
- **WHEN** the README badge row is rendered
- **THEN** badges point to `ci.yml` (label "CI") and `cli-release.yml` (label "CLI Release"), which match the workflow `name:` fields

---

## Business rules

- Java 21 (Temurin) via `actions/setup-java@v4` with Maven cache — never bare `mvn`, only `./mvnw`
- Node.js 22 with npm cache keyed on `linkedin-scraper/package-lock.json`
- Rust via `dtolnay/rust-toolchain@stable`; `Swatinem/rust-cache@v2` scoped to the `cli` workspace
- The backend job is hermetic: no database service containers — SQLite (temp file) is the only database in CI
- The `Code Quality` backend job is only reintroduced together with actual formatter/checkstyle plugins in `pom.xml`
- All YAML files end with a trailing newline
- CI/quality workflow commits use the `ci` commit type (added to `AGENTS.md`)

---

## Error cases

| Situation | Expected behavior |
|---|---|
| SQLite temp file cannot be created (disk/permissions) | Backend job fails on context boot — the integration tests own their datasource path |
| Flyway baseline drifts from entity mappings | Backend job fails on first SQLite query — `V1__baseline_schema.sql` must match the JPA entities |
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
1. ci.yml — no database service containers; the backend job is hermetic (SQLite via test `DynamicPropertySource`); ensure trailing newline.
2. code-quality.yml — remove the no-op backend job; ensure trailing newline.
3. cli-release.yml — add `GH_REPO: ${{ github.repository }}` to the upload step (the `release` job has no checkout); ensure trailing newline.
Also add the `ci` type to the commit convention table in AGENTS.md.
Do not modify pom.xml. Verify with a YAML parser afterward.
```
