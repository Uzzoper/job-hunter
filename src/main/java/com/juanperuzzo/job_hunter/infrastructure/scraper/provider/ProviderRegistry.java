package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProviderRegistry {

    private final Map<String, ProviderEntry> providers = new HashMap<>();

    public record ProviderEntry(
            ExtractionStrategy strategy,
            ExponentialBackoffRetry retry,
            RateLimiter rateLimiter,
            JobNormalizer normalizer) {}

    public void register(
            ExtractionStrategy strategy,
            ExponentialBackoffRetry retry,
            RateLimiter rateLimiter,
            JobNormalizer normalizer) {
        providers.put(strategy.providerId(),
                new ProviderEntry(strategy, retry, rateLimiter, normalizer));
    }

    public Optional<ProviderEntry> getProvider(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    public List<ProviderEntry> getAllProviders() {
        return List.copyOf(providers.values());
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
