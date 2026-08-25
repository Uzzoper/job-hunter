package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.infrastructure.persistence.UserEntity;
import com.juanperuzzo.job_hunter.infrastructure.persistence.UserJpaRepository;
import com.juanperuzzo.job_hunter.infrastructure.persistence.UserProfileEntity;
import com.juanperuzzo.job_hunter.infrastructure.persistence.UserProfileJpaRepository;
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
 * Baseline integration test for the SQLite local-first persistence spec
 * (docs/specs/sqlite-local-persistence.md, Scenario 2 — string-list columns round-trip).
 *
 * <p>Boots the full Spring context against a temp-file SQLite datasource, lets Flyway apply
 * the consolidated baseline schema, then saves and reloads a {@link UserProfileEntity} through its
 * JPA repository asserting that {@code skills} round-trips identically. Also proves that SQLite
 * connections run in WAL journal mode (spec Scenario 3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("SQLite baseline persistence integration test")
class SqliteBaselineIntegrationTest {

    private static final Path SQLITE_FILE = createTempSqliteFile();

    private static Path createTempSqliteFile() {
        try {
            var file = Files.createTempFile("jobhunter-sqlite-test-", ".db");
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
        registry.add("jwt.secret", () -> "test-secret-key-min-32-chars-long-for-hmac!!123");
        registry.add("OPENROUTER_API_KEY", () -> "sk-test-dummy-key");
        registry.add("RESEND_API_KEY", () -> "test-resend-key");
    }

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserProfileJpaRepository userProfileJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("should open SQLite connections in WAL journal mode")
    void connect_whenSqliteDatasource_shouldUseWalJournalMode() {
        var journalMode = jdbcTemplate.queryForObject("PRAGMA journal_mode", String.class);

        assertThat(journalMode).isEqualToIgnoringCase("wal");
    }

    @Test
    @DisplayName("should save and reload a user profile with skills through the JPA repository on SQLite")
    void saveAndReload_whenProfileWithSkills_shouldRoundTripSkills() {
        var user = userJpaRepository.save(
                new UserEntity(null, "sqlite-baseline@example.com", "SqliteTester", "$2a$hash"));

        var profile = new UserProfileEntity(
                null,
                user.getId(),
                "Java developer with Spring Boot experience building REST APIs.",
                new String[]{"Java", "Spring"},
                "FORMAL",
                null, null, null, null, null);
        var saved = userProfileJpaRepository.save(profile);

        var reloaded = userProfileJpaRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getUserId()).isEqualTo(user.getId());
        assertThat(reloaded.getSkills()).containsExactly("Java", "Spring");
    }
}
