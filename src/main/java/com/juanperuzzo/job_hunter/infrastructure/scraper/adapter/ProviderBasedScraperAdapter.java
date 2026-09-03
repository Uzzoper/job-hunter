package com.juanperuzzo.job_hunter.infrastructure.scraper.adapter;

import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperResult;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProviderBasedScraperAdapter implements ScraperPort {

    private static final Logger log = LoggerFactory.getLogger(ProviderBasedScraperAdapter.class);

    private static final int ERROR_MESSAGE_MAX_LENGTH = 300;

    private static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    /**
     * LinkedIn (Playwright microservice mode) regularly exceeds the uniform 60s budget
     * (~25 jobs via the detail-enrichment loop), so it gets an extended per-provider timeout.
     * InfoJobs also fetches detail pages for many jobs sequentially, requiring 120s.
     */
    private static final Duration LINKEDIN_PROVIDER_TIMEOUT = Duration.ofSeconds(180);

    private static final Duration INFOJOBS_PROVIDER_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Resolves the fetch timeout budget for a given provider id. gupy keeps the
     * default 60s; linkedin gets 180s; infojobs gets 120s.
     */
    public static Duration timeoutFor(String providerId) {
        return switch (providerId) {
            case "linkedin" -> LINKEDIN_PROVIDER_TIMEOUT;
            case "infojobs" -> INFOJOBS_PROVIDER_TIMEOUT;
            default -> DEFAULT_PROVIDER_TIMEOUT;
        };
    }

    private record ProviderResult(List<Job> jobs, ProviderFetchStats stats) {}

    private final ProviderRegistry registry;

    public ProviderBasedScraperAdapter(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ScraperResult fetch() {
        if (registry.isEmpty()) {
            log.warn("No providers registered, returning empty result");
            return new ScraperResult(List.of(), List.of());
        }

        var allJobs = new LinkedHashMap<String, Job>();
        var perProvider = new ArrayList<ProviderFetchStats>();
        var providers = registry.getAllProviders();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = providers.stream()
                    .map(entry -> {
                        var providerId = entry.strategy().providerId();
                        var timeout = timeoutFor(providerId);
                        return CompletableFuture.supplyAsync(() -> fetchProvider(entry), executor)
                                .orTimeout(timeout.toSeconds(), TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    var cause = ex.getCause() != null ? ex.getCause() : ex;
                                    log.warn("{}: provider fetch failed (timeout={}s): {}: {}",
                                            providerId, timeout.toSeconds(),
                                            cause.getClass().getSimpleName(), cause.getMessage(), cause);
                                    return new ProviderResult(List.of(),
                                            new ProviderFetchStats(providerId, 0, 0, 0, 0,
                                                    truncate(errorMessage(cause))));
                                });
                    })
                    .toList();

            for (var future : futures) {
                var result = future.join();
                for (var job : result.jobs()) {
                    allJobs.putIfAbsent(job.url(), job);
                }
                perProvider.add(result.stats());
            }
        }

        return new ScraperResult(List.copyOf(allJobs.values()), perProvider);
    }

    private ProviderResult fetchProvider(ProviderRegistry.ProviderEntry entry) {
        var providerId = entry.strategy().providerId();
        log.debug("Fetching from provider: {}", providerId);

        var rawJobs = entry.strategy().extract();
        var detailFailedCount = (int) rawJobs.stream()
                .filter(raw -> "true".equals(raw.metadata().get("detailFailed")))
                .count();

        var providerJobs = new ArrayList<Job>();
        if (entry.normalizer() != null) {
            var normalizer = entry.normalizer();
            for (var raw : rawJobs) {
                try {
                    var job = normalizer.normalize(raw);
                    if (job != null) {
                        providerJobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("{}: failed to normalize job {}: {}", providerId, raw.url(), e.getMessage());
                }
            }
        }

        log.info("{}: fetched {} raw jobs, normalized {}", providerId, rawJobs.size(), providerJobs.size());
        return new ProviderResult(providerJobs,
                new ProviderFetchStats(providerId, rawJobs.size(), 0, 0, detailFailedCount, null));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * Produces a non-null, truncated error message for {@code ProviderFetchStats.error}.
     * Falls back to the exception class name when the message is null.
     */
    private static String errorMessage(Throwable throwable) {
        var message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return message;
    }
}