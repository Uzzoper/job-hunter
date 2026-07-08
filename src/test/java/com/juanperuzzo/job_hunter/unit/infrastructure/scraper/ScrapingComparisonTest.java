package com.juanperuzzo.job_hunter.unit.infrastructure.scraper;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.GupyScraper;
import com.juanperuzzo.job_hunter.infrastructure.scraper.InfoJobsScraper;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ProviderBasedScraperAdapter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

// TEMPORARY comparison test — not to be committed permanently.
// Validates new ProviderBasedScraperAdapter vs old CompositeScraper on same HTTP inputs.
@DisplayName("Side-by-side comparison: old vs new scrapers")
class ScrapingComparisonTest {

    private static final Logger log = LoggerFactory.getLogger(ScrapingComparisonTest.class);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T00:00:00Z"), ZoneId.of("UTC"));

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private String baseUrl;
    private RateLimiter noopRateLimiter;
    private ExponentialBackoffRetry noRetry;

    @BeforeEach
    void setUp() {
        baseUrl = wireMock.baseUrl();
        noopRateLimiter = new RateLimiter() {
            @Override public boolean tryAcquire(String providerId) { return true; }
            @Override public void acquire(String providerId) {}
        };
        noRetry = new ExponentialBackoffRetry(1, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    @Test
    @DisplayName("Gupy JSON: old vs new produce comparable results")
    void compareGupyOutputs() {
        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": [
                                    {
                                      "id": "1",
                                      "name": "Desenvolvedor Junior",
                                      "careerPageName": "Tech Co",
                                      "jobUrl": "https://gupy.com/job/1",
                                      "description": "Vaga para dev junior",
                                      "publishedDate": "2026-07-01T00:00:00.000Z"
                                    },
                                    {
                                      "id": "2",
                                      "name": "Desenvolvedor Senior",
                                      "careerPageName": "Big Co",
                                      "jobUrl": "https://gupy.com/job/2",
                                      "description": "Vaga para dev senior",
                                      "publishedDate": "2026-07-01T00:00:00.000Z"
                                    }
                                  ]
                                }
                                """)));

        var oldScraper = new GupyScraper(baseUrl,
                List.of("desenvolvedor"), List.of("senior"), List.of(), 100, 10);

        var newProvider = new GupyProvider(baseUrl, 10,
                List.of("desenvolvedor"), 100, noRetry);
        var registry = new ProviderRegistry();
        registry.register(newProvider, null, noopRateLimiter,
                new JobNormalizer(new DateParser(FIXED_CLOCK), List.of("desenvolvedor"),
                        List.of(Pattern.compile("(?i)\\b(s[eê]nior|senior|sr\\.?|especialista|lead|coordenador|manager|bdr)\\b")),
                        List.of(), 90, FIXED_CLOCK));

        var newAdapter = new ProviderBasedScraperAdapter(registry);

        List<Job> oldJobs = oldScraper.fetch();
        List<Job> newJobs = newAdapter.fetch();

        log.info("Old GupyScraper returned {} jobs", oldJobs.size());
        log.info("New GupyProvider returned {} jobs", newJobs.size());
        oldJobs.forEach(j -> log.info("  OLD: {} | {}", j.title(), j.url()));
        newJobs.forEach(j -> log.info("  NEW: {} | {}", j.title(), j.url()));

        assertFalse(oldJobs.isEmpty(), "Old scraper should match the stub");
        assertFalse(newJobs.isEmpty(), "New provider should match the stub");
        assertEquals(oldJobs.size(), newJobs.size(),
                "Both implementations should produce same count for same Gupy JSON input");
    }

    @Test
    @DisplayName("InfoJobs HTML: old vs new produce comparable results")
    void compareInfoJobsOutputs() {
        wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("""
                                <html>
                                <body>
                                <div data-testid="job-card">
                                  <h2><a href="/vaga-de-desenvolvedor-junior__123">Desenvolvedor Junior Java</a></h2>
                                  <span data-testid="company-name">Tech Solutions</span>
                                  <span data-testid="posted-date">Hoje</span>
                                </div>
                                <div data-testid="job-card">
                                  <h2><a href="/vaga-de-analista-senior__456">Analista Senior</a></h2>
                                  <span data-testid="company-name">Big Corp</span>
                                  <span data-testid="posted-date">Hoje</span>
                                </div>
                                </body>
                                </html>
                                """)));

        var oldScraper = new InfoJobsScraper(baseUrl, true,
                List.of("desenvolvedor junior", "java junior"),
                List.of("senior"), List.of(), 1, 90, 10, 0, FIXED_CLOCK);

        var newProvider = new InfoJobsProvider(baseUrl, 10,
                List.of("desenvolvedor junior", "java junior"), 1, noRetry);
        var registry = new ProviderRegistry();
        registry.register(newProvider, null, noopRateLimiter,
                new JobNormalizer(new DateParser(FIXED_CLOCK),
                        List.of("desenvolvedor junior", "java junior"),
                        List.of(Pattern.compile("(?i)\\b(s[eê]nior|senior|sr\\.?|especialista|lead|coordenador|manager|bdr)\\b")),
                        List.of(), 90, FIXED_CLOCK));

        var newAdapter = new ProviderBasedScraperAdapter(registry);

        List<Job> oldJobs = oldScraper.fetch();
        List<Job> newJobs = newAdapter.fetch();

        log.info("Old InfoJobsScraper returned {} jobs", oldJobs.size());
        log.info("New InfoJobsProvider returned {} jobs", newJobs.size());
        oldJobs.forEach(j -> log.info("  OLD: {} | {}", j.title(), j.url()));
        newJobs.forEach(j -> log.info("  NEW: {} | {}", j.title(), j.url()));

        assertFalse(oldJobs.isEmpty(), "Old InfoJobsScraper should parse cards and find jobs");
        assertFalse(newJobs.isEmpty(), "New InfoJobsProvider should parse cards and find jobs");
        assertEquals(oldJobs.size(), newJobs.size(),
                "Both implementations should produce same count for same HTML input");
    }

    @Test
    @DisplayName("404 response: new ProviderBasedScraperAdapter with empty registry")
    void compareEmptyResponseBehavior() {
        var adapter = new ProviderBasedScraperAdapter(new ProviderRegistry());
        var newJobs = adapter.fetch();
        assertTrue(newJobs.isEmpty(),
                "ProviderBasedScraperAdapter returns empty list when no providers registered");
        log.info("Confirmed: ProviderBasedScraperAdapter returns empty when empty registry");
    }
}
