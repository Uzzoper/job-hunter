package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.adapter;

import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.model.Job;
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
import java.time.Duration;
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
            var jobs = adapter.fetch().jobs();

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
            var jobs = adapter.fetch().jobs();

            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("fetch should return empty when no providers registered")
        void fetch_whenNoProviders_shouldReturnEmpty() {
            adapter = new ProviderBasedScraperAdapter(registry);
            assertTrue(adapter.fetch().jobs().isEmpty());
            assertTrue(adapter.fetch().perProvider().isEmpty());
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
            var result = adapter.fetch();

            assertEquals(1, result.jobs().size());

            var failing = result.perProvider().stream()
                    .filter(s -> s.source().equals("failing")).findFirst().orElseThrow();
            assertEquals(0, failing.fetched());
            assertNotNull(failing.error());
            assertEquals("fail", failing.error());
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
            var result = adapter.fetch();

            assertTrue(result.jobs().isEmpty());
            assertEquals(2, result.perProvider().size());
            assertTrue(result.perProvider().stream().allMatch(s -> s.error() != null));
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
            var jobs = adapter.fetch().jobs();

            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("fetch should fall back to exception class name in error field when message is null")
        void fetch_whenProviderFailsWithNullMessage_shouldUseClassNameAsError() {
            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "silent"; }
                @Override public List<RawJob> extract() { throw new IllegalStateException(); }
            }, null, noopRateLimiter, createNormalizer(List.of()));

            adapter = new ProviderBasedScraperAdapter(registry);
            var result = adapter.fetch();

            var stats = result.perProvider().stream()
                    .filter(s -> s.source().equals("silent")).findFirst().orElseThrow();
            assertNotNull(stats.error());
            assertEquals("java.lang.IllegalStateException", stats.error());
        }
    }

    @Nested
    @DisplayName("Scenario: per-provider timeout budget")
    class TimeoutBudget {

        @Test
        @DisplayName("timeoutFor should give linkedin the extended budget and others the default")
        void timeoutFor_whenProvider_shouldReturnPerProviderBudget() {
            assertEquals(Duration.ofSeconds(180), ProviderBasedScraperAdapter.timeoutFor("linkedin"));
            assertEquals(Duration.ofSeconds(60), ProviderBasedScraperAdapter.timeoutFor("gupy"));
            assertEquals(Duration.ofSeconds(60), ProviderBasedScraperAdapter.timeoutFor("infojobs"));
            assertEquals(Duration.ofSeconds(60), ProviderBasedScraperAdapter.timeoutFor("unknown"));
        }
    }

    @Nested
    @DisplayName("Scenario: parallel provider execution")
    class ParallelExecution {

        @Test
        @DisplayName("fetch should run providers in parallel so slow one does not block fast one")
        void fetch_whenOneProviderSlow_shouldNotBlockOthers() {
            // Slow provider: sleeps 2s
            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "slow"; }
                @Override public List<RawJob> extract() {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return List.of(raw("SlowDev", "https://slow.com/1"));
                }
            }, null, noopRateLimiter, createNormalizer(List.of("slow")));

            // Second provider: also sleeps 2s — sequential sum would be ~4s, parallel max ~2s
            registry.register(new ExtractionStrategy() {
                @Override public String providerId() { return "other"; }
                @Override public List<RawJob> extract() {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return List.of(raw("OtherDev", "https://other.com/1"));
                }
            }, null, noopRateLimiter, createNormalizer(List.of("other")));

            adapter = new ProviderBasedScraperAdapter(registry);

            var start = System.currentTimeMillis();
            var result = adapter.fetch();
            var elapsed = System.currentTimeMillis() - start;

            // Both providers should be represented in perProvider stats
            assertEquals(2, result.perProvider().size());
            assertTrue(result.perProvider().stream().anyMatch(s -> s.source().equals("slow")));
            assertTrue(result.perProvider().stream().anyMatch(s -> s.source().equals("other")));

            // Sequential sum ≈ 4s; parallel max ≈ 2s. Assert < 2.5s to prove parallelism.
            assertTrue(elapsed < 2500,
                    "parallel fetch should take ~2s (max), not ~4s (sum); actual=" + elapsed + "ms");
        }
    }

    @Nested
    @DisplayName("Scenario 17: per-provider fetch statistics")
    class FetchStats {

        @Test
        @DisplayName("fetch should report fetched and detailFailedCount per provider")
        void fetch_whenMixedProviders_shouldReturnPerProviderStats() {
            registry.register(createStub("p1",
                            rawWithMetadata("Dev1", "https://a.com/1", null),
                            rawWithMetadata("Dev2", "https://a.com/2", "true")),
                    null, noopRateLimiter, createNormalizer(List.of("dev1", "dev2")));
            registry.register(createStub("p2", raw("Dev3", "https://a.com/3")),
                    null, noopRateLimiter, createNormalizer(List.of("dev3")));

            adapter = new ProviderBasedScraperAdapter(registry);
            var result = adapter.fetch();

            var p1 = result.perProvider().stream()
                    .filter(s -> s.source().equals("p1")).findFirst().orElseThrow();
            assertEquals(2, p1.fetched());
            assertEquals(1, p1.detailFailedCount());
            assertNull(p1.error());

            var p2 = result.perProvider().stream()
                    .filter(s -> s.source().equals("p2")).findFirst().orElseThrow();
            assertEquals(1, p2.fetched());
            assertEquals(0, p2.detailFailedCount());
        }

        @Test
        @DisplayName("fetch should NOT call the company site enricher in the hot path")
        void fetch_whenJobsHaveCompanyWebsite_shouldNotCallEnricher() {
            registry.register(createStub("p1", raw("Dev1", "https://a.com/1")),
                    null, noopRateLimiter, createNormalizer(List.of("dev1")));

            var enricher = new CompanySiteEnrichmentPort() {
                @Override
                public Job enrich(Job job) {
                    throw new AssertionError("enricher must not be called from fetch hot path");
                }
            };
            adapter = new ProviderBasedScraperAdapter(registry, enricher);
            var jobs = adapter.fetch().jobs();

            // No exception thrown => enricher was never invoked from fetch().
            assertEquals(1, jobs.size());
            assertNull(jobs.get(0).contactEmail());
        }
    }

    private static RawJob raw(String title, String url) {
        return rawWithMetadata(title, url, null);
    }

    private static RawJob rawWithMetadata(String title, String url, String detailFailed) {
        var metadata = new java.util.HashMap<String, String>();
        if (detailFailed != null) {
            metadata.put("detailFailed", detailFailed);
        }
        return new RawJob(title, "Co", url, "desc", "2026-07-01", null, null, "test", metadata);
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