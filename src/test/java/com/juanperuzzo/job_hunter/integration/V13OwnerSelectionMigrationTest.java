package com.juanperuzzo.job_hunter.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("V13 owner selection migration tests")
class V13OwnerSelectionMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("migration should preserve the configured owner's data")
    void migration_whenMultipleUsersAndOwnerConfigured_shouldKeepOwnerData() throws Exception {
        var databaseUrl = migrateToVersion12("owner-selected.db");
        insertTwoUserFixture(databaseUrl);

        flyway(databaseUrl, "13", "20").migrate();

        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement()) {
            assertEquals("Owner", stringValue(statement, "SELECT name FROM profile"));
            assertEquals("owner@example.com", stringValue(statement, "SELECT email FROM profile"));
            assertEquals("owner resume", stringValue(statement, "SELECT resume_text FROM profile"));
            assertEquals(90, intValue(statement, "SELECT match_score FROM job_analyses WHERE job_id = 1"));
            assertEquals("owner draft", stringValue(statement, "SELECT body FROM email_drafts WHERE job_id = 1"));
            assertEquals("Owner project", stringValue(statement, "SELECT name FROM user_projects"));
        }
    }

    @Test
    @DisplayName("migration should refuse an ambiguous multi-user database")
    void migration_whenMultipleUsersAndOwnerMissing_shouldFailWithoutSelectingAnAccount() throws Exception {
        var databaseUrl = migrateToVersion12("ambiguous-owner.db");
        insertTwoUsers(databaseUrl);

        assertThrows(RuntimeException.class, () -> flyway(databaseUrl, "13", "").migrate());
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var results = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            results.next();
            assertEquals(2, results.getInt(1));
        }
    }

    private String migrateToVersion12(String databaseName) {
        var databaseUrl = "jdbc:sqlite:" + tempDir.resolve(databaseName).toAbsolutePath();
        flyway(databaseUrl, "12", "").migrate();
        return databaseUrl;
    }

    private static Flyway flyway(String databaseUrl, String target, String legacyOwnerUserId) {
        return Flyway.configure()
                .dataSource(databaseUrl, "", "")
                .locations("classpath:db/migration")
                .placeholders(Map.of("legacyOwnerUserId", legacyOwnerUserId))
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static void insertTwoUserFixture(String databaseUrl) throws Exception {
        insertTwoUsers(databaseUrl);
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO user_profiles (user_id, resume_text, skills, tone)
                    VALUES (10, 'other resume', '["Rust"]', 'FORMAL'),
                           (20, 'owner resume', '["Java"]', 'CASUAL')
                    """);
            statement.executeUpdate("""
                    INSERT INTO jobs (id, title, company, url, posted_at, source)
                    VALUES (1, 'Developer', 'Example', 'https://example.test/job', '2026-11-26', 'TEST')
                    """);
            statement.executeUpdate("""
                    INSERT INTO job_analyses (id, job_id, user_id, match_score, matched_skills,
                                              missing_skills, company_tone, summary)
                    VALUES (1, 1, 10, 10, '[]', '[]', 'FORMAL', 'other analysis'),
                           (2, 1, 20, 90, '["Java"]', '[]', 'CASUAL', 'owner analysis')
                    """);
            statement.executeUpdate("""
                    INSERT INTO email_drafts (id, job_id, user_id, subject, body, status, recipient_email)
                    VALUES (1, 1, 10, 'Other', 'other draft', 'PENDING', 'other@example.com'),
                           (2, 1, 20, 'Owner', 'owner draft', 'PENDING', 'owner@example.com')
                    """);
            statement.executeUpdate("""
                    INSERT INTO user_projects (user_id, name, description, tech_stack)
                    VALUES (10, 'Other project', 'Other account project', 'Rust'),
                           (20, 'Owner project', 'Owner account project', 'Java')
                    """);
        }
    }

    private static void insertTwoUsers(String databaseUrl) throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, email, name, password_hash)
                    VALUES (10, 'other@example.com', 'Other', 'hash'),
                           (20, 'owner@example.com', 'Owner', 'hash')
                    """);
        }
    }

    private static String stringValue(Statement statement, String sql) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError("No row returned for: " + sql);
            }
            return results.getString(1);
        }
    }

    private static int intValue(Statement statement, String sql) throws Exception {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new AssertionError("No row returned for: " + sql);
            }
            return results.getInt(1);
        }
    }
}
