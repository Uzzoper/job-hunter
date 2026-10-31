package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.JobHunterApplication;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyMcpProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for {@code docs/specs/fase-a-sourcing-restoration.md} A1 Scenarios 1-2:
 * with default config the {@link ProviderRegistry} must hold the
 * {@link GupyMcpProvider} for {@code gupy}, the legacy REST {@code gupyProvider}
 * bean must NOT be registered (dead gateway, probed 404), and REST comes back
 * only with {@code gupy.rest.enabled=true}. The MCP keywords must come from
 * {@code scraper.gupy.keywords} (single canonical list).
 *
 * <p>{@code HERMES_API_KEY} is supplied as a dummy so the mandatory fail-fast key
 * resolves at startup (same trick as {@link AiPortWiringTest}).
 */
@DisplayName("Gupy provider wiring integration test (fase A — MCP default-on)")
class GupyProviderWiringTest {

    private static final List<String> SCRAPER_GUPY_KEYWORDS = List.of(
            "desenvolvedor", "programador", "estágio", "junior", "ti", "tecnologia", "dev", "software", "analista");

    private ConfigurableApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private void boot(String... extraArgs) throws IOException {
        var sqliteFile = Files.createTempFile("jobhunter-gupy-wiring-test-", ".db");
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

    private static ExtractionStrategy registryGupyStrategy(ProviderRegistry registry) {
        return registry.getProvider("gupy")
                .orElseThrow(() -> new AssertionError("registry must hold a gupy provider"))
                .strategy();
    }

    @Test
    @DisplayName("default wiring when only scraper.gupy.keywords is present should register GupyMcpProvider and no gupyProvider bean")
    void defaultWiring_usesGupyMcpProvider() throws IOException {
        boot();

        var registry = context.getBean("providerRegistry", ProviderRegistry.class);

        assertThat(registryGupyStrategy(registry))
                .as("the gupy slot must hold the MCP provider by default")
                .isInstanceOf(GupyMcpProvider.class);
        assertThat(context.containsBean("gupyProvider"))
                .as("the dead REST gateway bean must not be registered by default")
                .isFalse();
    }

    @Test
    @DisplayName("with gupy.enabled=false and gupy.rest.enabled=true the REST gupyProvider bean is resurrected and used")
    void restEnabledProperty_resurrectsGupyProvider() throws IOException {
        boot("--gupy.enabled=false", "--gupy.rest.enabled=true");

        var registry = context.getBean("providerRegistry", ProviderRegistry.class);

        assertThat(registryGupyStrategy(registry))
                .as("with MCP off and rest enabled, the REST provider must serve the gupy slot")
                .isInstanceOf(GupyProvider.class);
        assertThat(context.containsBean("gupyProvider"))
                .as("the REST bean must exist under its regression-hatch property")
                .isTrue();
    }

    @Test
    @DisplayName("GupyMcpProvider keywords should come from scraper.gupy.keywords (9 terms, no gupy.keywords second home)")
    void mcpKeywords_comeFromScraperGupyKeywords() throws IOException {
        boot();

        var registryGupy = registryGupyStrategy(context.getBean("providerRegistry", ProviderRegistry.class));
        assertThat(registryGupy).isInstanceOf(GupyMcpProvider.class);

        var keywords = mcpKeywords((GupyMcpProvider) registryGupy);

        assertThat(keywords)
                .as("the MCP provider must plan queries with the 9-term scraper.gupy.keywords list")
                .containsExactlyElementsOf(SCRAPER_GUPY_KEYWORDS);
    }

    /** Reads the MCP provider's planned keyword list (spec gupy-mcp-provider §9, fase-a Scenario 2). */
    private static List<String> mcpKeywords(GupyMcpProvider provider) {
        return provider.keywords();
    }
}
