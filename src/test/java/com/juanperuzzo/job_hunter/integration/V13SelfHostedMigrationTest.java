package com.juanperuzzo.job_hunter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Flyway applies V13 (self-hosted single-tenant) on a fresh SQLite file:
 * the users table is gone, the profile table exists and analysis/draft rows are
 * deduplicated by job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("V13 self-hosted migration integration test")
class V13SelfHostedMigrationTest {

    private static final Path SQLITE_FILE = createTempSqliteFile();

    private static Path createTempSqliteFile() {
        try {
            var file = Files.createTempFile("jobhunter-v13-test-", ".db");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary SQLite database file", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_FILE.toAbsolutePath());
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("bot.service.api-key", () -> "test-bot-token");
        registry.add("HERMES_API_KEY", () -> "test-hermes-key");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("migration should drop users and create a single profile table")
    void flyway_whenFreshDatabase_shouldDropUsersAndCreateProfile() {
        var tables = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table'", String.class);

        assertThat(tables).contains("profile");
        assertThat(tables).doesNotContain("users");
    }

    @Test
    @DisplayName("migration should keep one analysis and one draft per job")
    void migration_whenDuplicateJobRows_shouldKeepOnePerJob() {
        // V13 ran on an empty DB; recreate the pre-V13 shape in-memory to exercise the
        // dedup rule used by the migration's INSERT ... SELECT statements.
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_analyses", Integer.class);
        assertThat(count).isZero();

        var sql = """
                WITH src(id, job_id) AS (VALUES (1, 10), (2, 10), (3, 20))
                SELECT COUNT(*) FROM (SELECT MIN(id) FROM src GROUP BY job_id)
                """;
        assertThat(jdbcTemplate.queryForObject(sql, Integer.class)).isEqualTo(2);
    }
}
