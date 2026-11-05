package com.juanperuzzo.job_hunter.unit.infrastructure.config;

import com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient;
import com.juanperuzzo.job_hunter.infrastructure.config.AppConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring for the optional dedicated Hermes profile for ingest
 * ({@code docs/specs/linkedin-posts-ingest.md}, 2026-11-05 round): with
 * {@code hermes.ingest-base-url} the ingest classifier gets a dedicated
 * {@link HermesAgentClient}; without it, it resolves to the shared profile
 * and startup logs a WARN. The profile itself is provisioned on the VM —
 * this repo only consumes the property.
 */
@DisplayName("Ingest Hermes profile wiring")
class IngestAiProfileWiringTest {

    private static final String SHARED = "http://127.0.0.1:8642/p/jobhunter-bot/v1";
    private static final String DEDICATED = "http://127.0.0.1:8642/p/jobhunter-ingest/v1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppConfig.IngestAiConfig.class)
            .withPropertyValues(
                    "hermes.base-url=" + SHARED,
                    "hermes.api-key=test-key",
                    "hermes.model=default",
                    "hermes.timeout-seconds=120");

    @Test
    @DisplayName("without hermes.ingest-base-url the ingest client falls back to the shared profile and WARNs")
    void wiring_withoutIngestBaseUrl_shouldFallBackToSharedProfileAndWarn() {
        var warnEvents = captureWarnEvents();

        runner.run(context -> {
            assertTrue(context.containsBean("ingestAiPort"));
            var client = (HermesAgentClient) context.getBean("ingestAiPort");
            assertEquals(SHARED, client.baseUrl(),
                    "absent ingest property must resolve to the shared profile");
        });

        assertTrue(warnEvents.list.stream().anyMatch(e -> e.getFormattedMessage().contains("hermes.ingest-base-url")),
                "startup must WARN when ingest rides the shared profile");
    }

    @Test
    @DisplayName("with hermes.ingest-base-url a dedicated client is used and no WARN fires")
    void wiring_withIngestBaseUrl_shouldUseDedicatedProfileWithoutWarn() {
        var warnEvents = captureWarnEvents();

        runner.withPropertyValues("hermes.ingest-base-url=" + DEDICATED).run(context -> {
            var client = (HermesAgentClient) context.getBean("ingestAiPort");
            assertEquals(DEDICATED, client.baseUrl(),
                    "set ingest property must wire the dedicated profile");
        });

        assertFalse(warnEvents.list.stream().anyMatch(e -> e.getFormattedMessage().contains("hermes.ingest-base-url")),
                "dedicated profile must not trip the shared-profile warning");
    }

    private ListAppender<ILoggingEvent> captureWarnEvents() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(AppConfig.IngestAiConfig.class);
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        return appender;
    }
}
