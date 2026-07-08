package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProviderRegistry tests")
class ProviderRegistryTest {

    private ProviderRegistry registry;
    private TokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
        rateLimiter = new TokenBucketRateLimiter(10, 5, java.util.Map.of());
    }

    @Test
    @DisplayName("should register and retrieve a provider")
    void shouldRegisterAndRetrieve() {
        var retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        var parser = new DateParser();
        var normalizer = new JobNormalizer(parser, List.of("java"), List.of(), List.of(), 90,
                java.time.Clock.systemUTC());

        ExtractionStrategy strategy = new ExtractionStrategy() {
            @Override public String providerId() { return "test"; }
            @Override public List<com.juanperuzzo.job_hunter.application.port.out.RawJob> extract() { return List.of(); }
        };

        registry.register(strategy, retry, rateLimiter, normalizer);

        assertFalse(registry.isEmpty());
        assertTrue(registry.getProvider("test").isPresent());
    }

    @Test
    @DisplayName("should return empty for unknown provider")
    void shouldReturnEmptyForUnknown() {
        assertTrue(registry.getProvider("unknown").isEmpty());
    }

    @Test
    @DisplayName("should return all registered providers")
    void shouldReturnAllProviders() {
        registry.register(newStub("a"), null, rateLimiter, null);
        registry.register(newStub("b"), null, rateLimiter, null);

        assertEquals(2, registry.getAllProviders().size());
    }

    @Test
    @DisplayName("isEmpty should be true for empty registry")
    void isEmptyShouldBeTrueForEmpty() {
        assertTrue(registry.isEmpty());
    }

    private static ExtractionStrategy newStub(String id) {
        return new ExtractionStrategy() {
            @Override public String providerId() { return id; }
            @Override public List<com.juanperuzzo.job_hunter.application.port.out.RawJob> extract() { return List.of(); }
        };
    }
}
