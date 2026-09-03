package com.juanperuzzo.job_hunter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the shared scraping RestClient and the InfoJobs detail RestClient are wired
 * as two distinct beans (not the same instance). The detail one is built from
 * {@code scraper.infojobs.detail-timeout-seconds} (5s) while the shared one comes from
 * {@code scraper.rest-client.timeout-seconds} (10s); the behavioral proof that detail
 * fetches honor the shorter dedicated timeout lives in {@code InfoJobsProviderTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("AppConfig RestClient wiring integration test")
class AppConfigRestClientTest {

    private static final Path SQLITE_FILE = createTempSqliteFile();

    private static Path createTempSqliteFile() {
        try {
            var file = Files.createTempFile("jobhunter-restclient-test-", ".db");
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
        registry.add("HERMES_API_KEY", () -> "test-hermes-key");
    }

    @Autowired
    @Qualifier("scraperRestClient")
    private RestClient scraperRestClient;

    @Autowired
    @Qualifier("infojobsDetailRestClient")
    private RestClient infojobsDetailRestClient;

    @Test
    @DisplayName("shared and InfoJobs detail RestClients should be wired as two distinct beans")
    void restClients_whenWired_shouldBeDistinctBeans() {
        assertNotNull(scraperRestClient, "shared scraperRestClient should be wired");
        assertNotNull(infojobsDetailRestClient, "infojobsDetailRestClient should be wired");
        // Two distinct beans: the detail client is a separate bean from the shared one,
        // so its shorter timeout never leaks into search-page fetches (and vice-versa).
        assertNotSame(scraperRestClient, infojobsDetailRestClient);
    }
}
