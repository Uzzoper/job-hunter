package com.juanperuzzo.job_hunter.infrastructure.scraper.adapter;

import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperResult;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProviderBasedScraperAdapter implements ScraperPort {

    private static final Logger log = LoggerFactory.getLogger(ProviderBasedScraperAdapter.class);

    private static final int ERROR_MESSAGE_MAX_LENGTH = 300;

    private static final int PROVIDER_TIMEOUT_SECONDS = 60;

    private record ProviderResult(List<Job> jobs, ProviderFetchStats stats) {}

    private final ProviderRegistry registry;

    /**
     * Kept for backward compatibility with existing call sites/tests. The company-site
     * enricher no longer runs inside the synchronous fetch hot path (async-company-enrichment
     * spec); the argument is ignored. Enrichment now runs out-of-band via
     * {@code CompanyEnrichmentService}.
     */
    @SuppressWarnings("unused")
    private final CompanySiteEnrichmentPort enricher;

    public ProviderBasedScraperAdapter(ProviderRegistry registry) {
        this(registry, null);
    }

    public ProviderBasedScraperAdapter(ProviderRegistry registry, CompanySiteEnrichmentPort enricher) {
        this.registry = registry;
        this.enricher = enricher;
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
                    .map(entry -> CompletableFuture.supplyAsync(() -> fetchProvider(entry), executor)
                            .orTimeout(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                var providerId = entry.strategy().providerId();
                                var cause = ex.getCause() != null ? ex.getCause() : ex;
                                log.error("{}: provider timed out or failed: {}", providerId, cause.getMessage());
                                return new ProviderResult(List.of(),
                                        new ProviderFetchStats(providerId, 0, 0, 0, 0, truncate(cause.getMessage())));
                            }))
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
}