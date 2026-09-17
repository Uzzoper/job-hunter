package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for {@code docs/specs/hermes-only-ai.md} Scenario 1: with {@code ai.provider}
 * ABSENT the application context must boot and expose exactly one {@link AiPort} bean,
 * and that bean must be a {@link HermesAgentClient}. {@code ai.provider} no longer exists;
 * the Hermes bean is wired unconditionally.
 *
 * <p>{@code HERMES_API_KEY} is supplied as a dummy so the mandatory fail-fast key resolves
 * at startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("AiPort wiring integration test (Hermes-only)")
class AiPortWiringTest {

    private static final Path SQLITE_FILE = createTempSqliteFile();

    private static Path createTempSqliteFile() {
        try {
            var file = Files.createTempFile("jobhunter-aiport-wiring-test-", ".db");
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
        registry.add("HERMES_API_KEY", () -> "test-hermes-key");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiPort aiPort;

    @Test
    @DisplayName("AiPort when ai.provider is absent should resolve to a single HermesAgentClient bean")
    void aiPort_whenAiProviderAbsent_shouldResolveToHermesAgentClient() {
        var aiPortBeans = applicationContext.getBeansOfType(AiPort.class);

        assertThat(aiPortBeans)
                .as("exactly one AiPort bean must exist")
                .hasSize(1);
        assertThat(aiPort)
                .as("the sole AiPort bean must be the Hermes client")
                .isInstanceOf(HermesAgentClient.class);
    }
}
