package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.JobHunterApplication;
import com.juanperuzzo.job_hunter.infrastructure.config.LinkedInScraperProperties;
import com.juanperuzzo.job_hunter.infrastructure.scraper.client.LinkedInScraperClient;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for {@code docs/specs/fase-a-sourcing-restoration.md} A3 Scenario 8:
 * {@code scraper.linkedin.enabled} must gate BOTH LinkedIn extraction beans
 * (jsoup provider and service client) — mode selects <em>which</em>,
 * enabled selects <em>whether</em> (default {@code true}). Also verifies the
 * jsoup fallback path gets a bound {@code scraper.linkedin.jsoup.max-pages}
 * key (previously record-default unbound).
 */
@DisplayName("LinkedIn beans gating integration test (fase A Scenario 8)")
class LinkedInWiringTest {

    private ConfigurableApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private void boot(String... extraArgs) throws IOException {
        var sqliteFile = Files.createTempFile("jobhunter-linkedin-wiring-test-", ".db");
        sqliteFile.toFile().deleteOnExit();

        var args = new ArrayList<String>(List.of(
                "--spring.datasource.url=jdbc:sqlite:" + sqliteFile.toAbsolutePath(),
                "--spring.datasource.driver-class-name=org.sqlite.JDBC",
                "--spring.datasource.username=",
                "--spring.datasource.password=",
                "--jwt.secret=test-secret-key-min-32-chars-long-for-hmac!!123",
                "--HERMES_API_KEY=test-hermes-key"));
        for (var extra : extraArgs) {
            args.add(extra);
        }

        context = new SpringApplicationBuilder(JobHunterApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    @Test
    @DisplayName("with scraper.linkedin.enabled=false no LinkedIn ExtractionStrategy bean is registered (jsoup or service)")
    void boot_withEnabledFalse_registersNoLinkedinBeans() throws IOException {
        boot("--scraper.linkedin.enabled=false");

        var registry = context.getBean("providerRegistry", ProviderRegistry.class);

        assertThat(registry.getProvider("linkedin"))
                .as("the registry must hold no linkedin provider when the flag is off")
                .isEmpty();
        assertThat(context.getBeansOfType(LinkedInScraperClient.class))
                .as("the service-mode LinkedIn client must not be registered")
                .isEmpty();
    }

    @Test
    @DisplayName("with the shipped yaml the LinkedIn strategy is registered and scraper.linkedin.jsoup.max-pages binds")
    void boot_withShippedYaml_registersStrategyAndBindsJsoupMaxPages() throws IOException {
        boot();

        var registry = context.getBean("providerRegistry", ProviderRegistry.class);
        assertThat(registry.getProvider("linkedin"))
                .as("yaml ships scraper.linkedin.enabled=true + mode=service → the service client serves linkedin")
                .isPresent();

        var props = context.getBean(LinkedInScraperProperties.class);
        assertThat(jsoupMaxPages(props))
                .as("the jsoup fallback must get a bound max-pages key (was record-default unbound)")
                .isEqualTo(3);
    }

    /** Nested jsoup binding check ({@code scraper.linkedin.jsoup.max-pages}, fase-a Scenario 8). */
    private static int jsoupMaxPages(LinkedInScraperProperties props) {
        return props.jsoup().maxPages();
    }
}
