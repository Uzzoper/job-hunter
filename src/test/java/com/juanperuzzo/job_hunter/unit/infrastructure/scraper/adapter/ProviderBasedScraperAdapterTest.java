package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.adapter;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.adapter.ProviderBasedScraperAdapter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProviderBasedScraperAdapter tests")
class ProviderBasedScraperAdapterTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-08T00:00:00Z"), ZoneId.of("UTC"));

    private ProviderRegistry registry;
    private ProviderBasedScraperAdapter adapter;
    private RateLimiter noopRateLimiter;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
        noopRateLimiter = new RateLimiter() {
            @Override public boolean tryAcquire(String providerId) { return true; }
            @Override public void acquire(String providerId) {}
        };
    }

    @Nested
    @DisplayName("Scenario 1: fetching with providers")
    class Fetching {

        @Test
        @DisplayName("fetch should return jobs from all providers")
        void fetch_whenProvidersHaveJobs_shouldReturnAll() {
            registry.register(createStub("p1", raw("Dev1", "https://a.com/1")),
                    null, noopRateLimiter, createNormalizer(List.of("dev1")));
            registry.register(createStub("p2", raw("Dev2", "https://a.com/2")),
                    null, noopRateLimiter, createNormalizer(List.of("dev2")));

            adapter = new ProviderBasedScraperAdapter(registry);
            var jobs = adapter.fetch();

            assertEquals(2, jobs.size());
        }

        @Test
        @DisplayName("fetch should deduplicate by URL across providers")
        void fetch_whenSameUrlAcrossProviders_shouldDeduplicate() {
            registry.register(createStub("p1", raw("Dev1", "https://a.com/1")),
                    null, noopRateLimiter, createNormalizer(List.of("dev1")));
            registry.register(createStub("p2", raw("Dev1", "https://a.com/1")),
                    null, noopRateLimiter, createNormalizer(List.of("dev1")));

            adapter = new ProviderBasedScraperAdapter(registry);
            var jobs = adapter.fetch();

            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("fetch should return empty when no providers registered")
        void fetch_whenNoProviders_shouldReturnEmpty() {
            adapter = new ProviderBasedScraperAdapter(registry);
            assertTrue(adapter.fetch().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 2: error handling")
    class ErrorHandling {

        @Test
        @DisplayName("fetch should continue when one provider fails")
        void fetch_whenOneProviderFails_shouldContinue() {
            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "failing"; }
                @Override public List<RawJob> extract() { throw new RuntimeException("fail"); }
            }, null, noopRateLimiter, createNormalizer(List.of()));

            registry.register(createStub("good", raw("Dev", "https://a.com/1")),
                    null, noopRateLimiter, createNormalizer(List.of("dev")));

            adapter = new ProviderBasedScraperAdapter(registry);
            var jobs = adapter.fetch();

            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("fetch should return empty when all providers fail")
        void fetch_whenAllProvidersFail_shouldReturnEmpty() {
            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "f1"; }
                @Override public List<RawJob> extract() { throw new RuntimeException("fail1"); }
            }, null, noopRateLimiter, createNormalizer(List.of()));

            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "f2"; }
                @Override public List<RawJob> extract() { throw new RuntimeException("fail2"); }
            }, null, noopRateLimiter, createNormalizer(List.of()));

            adapter = new ProviderBasedScraperAdapter(registry);
            var jobs = adapter.fetch();

            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("fetch should return partial results when normalizer fails for some jobs")
        void fetch_whenNormalizerFails_shouldReturnPartialResults() {
            registry.register(createStub("p1",
                            raw("Dev1", "https://a.com/1"),
                            raw("Bad", "https://a.com/2")),
                    null, noopRateLimiter,
                    new JobNormalizer(new DateParser(FIXED_CLOCK), List.of("dev1"),
                            List.of(), List.of(), 90, FIXED_CLOCK));

            adapter = new ProviderBasedScraperAdapter(registry);
            var jobs = adapter.fetch();

            assertEquals(1, jobs.size());
        }
    }

    private static RawJob raw(String title, String url) {
        return new RawJob(title, "Co", url, "desc", "2026-07-01", null, null, "test", null);
    }

    private static ExtractionStrategy createStub(String id, RawJob... jobs) {
        return new ExtractionStrategy() {
            @Override public String providerId() { return id; }
            @Override public List<RawJob> extract() { return List.of(jobs); }
        };
    }

    private static JobNormalizer createNormalizer(List<String> keywords) {
        return new JobNormalizer(new DateParser(FIXED_CLOCK), keywords, List.of(), List.of(), 90, FIXED_CLOCK);
    }
}
