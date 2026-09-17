package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.JobHunterApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Post-change contract for {@code docs/specs/hermes-only-ai.md} Scenario 2: Hermes is the
 * only AI backend, so {@code HERMES_API_KEY} is mandatory and the context must fail fast
 * (unresolved placeholder) naming that key — even for non-AI flows, with no silent
 * fallback provider.
 *
 * <p>With OpenRouter and Ollama removed, the unconditional Hermes client's
 * {@code ${HERMES_API_KEY}} placeholder is the first unresolved AI key, so the startup
 * error names it.
 *
 * <p>SQLite and JWT are supplied as command-line arguments (highest precedence) so the
 * context reaches bean creation deterministically; {@code HERMES_API_KEY} is intentionally
 * left unset in the environment. The test assumes the JVM environment does not export
 * {@code HERMES_API_KEY} (true for CI and the default dev shell).
 */
@DisplayName("Hermes config fail-fast integration test")
class HermesConfigFailFastTest {

    @Test
    @DisplayName("context startup when HERMES_API_KEY is unset should fail naming the key")
    void context_whenHermesApiKeyUnset_shouldFailNamingTheKey() throws IOException {
        var sqliteFile = Files.createTempFile("jobhunter-hermes-failfast-test-", ".db");
        sqliteFile.toFile().deleteOnExit();

        var thrown = catchThrowable(() -> {
            var context = new SpringApplicationBuilder(JobHunterApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=jdbc:sqlite:" + sqliteFile.toAbsolutePath(),
                            "--spring.datasource.driver-class-name=org.sqlite.JDBC",
                            "--spring.datasource.username=",
                            "--spring.datasource.password=",
                            "--jwt.secret=test-secret-key-min-32-chars-long-for-hmac!!123");
            // Only reached if startup unexpectedly succeeds; close so no context leaks.
            context.close();
        });

        assertThat(thrown)
                .as("context must not start without HERMES_API_KEY")
                .isNotNull();
        assertThat(allMessages(thrown))
                .as("startup failure must name HERMES_API_KEY")
                .contains("HERMES_API_KEY");
    }

    /**
     * Flattens the throwable cause chain so the assertion can find the placeholder error
     * regardless of how Spring Boot wraps it (e.g. {@code ApplicationContextException}).
     */
    private static String allMessages(Throwable thrown) {
        var messages = new StringBuilder();
        for (var current = thrown; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
