package com.juanperuzzo.job_hunter.infrastructure.scraper;

import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;

public class ProviderBasedScraperAdapter implements ScraperPort {

    private static final Logger log = LoggerFactory.getLogger(ProviderBasedScraperAdapter.class);

    private final ProviderRegistry registry;

    public ProviderBasedScraperAdapter(ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<Job> fetch() {
        if (registry.isEmpty()) {
            log.warn("No providers registered, returning empty list");
            return List.of();
        }

        var allJobs = new LinkedHashMap<String, Job>();

        for (var entry : registry.getAllProviders()) {
            var providerId = entry.strategy().providerId();
            try {
                log.debug("Fetching from provider: {}", providerId);

                var rawJobs = entry.strategy().extract();

                if (entry.normalizer() != null) {
                    var normalizer = entry.normalizer();
                    for (var raw : rawJobs) {
                        try {
                            var job = normalizer.normalize(raw);
                            if (job != null) {
                                allJobs.putIfAbsent(job.url(), job);
                            }
                        } catch (Exception e) {
                            log.warn("{}: failed to normalize job {}: {}", providerId, raw.url(), e.getMessage());
                        }
                    }
                }

                log.info("{}: fetched {} raw jobs, total unique: {}", providerId, rawJobs.size(), allJobs.size());
            } catch (Exception e) {
                log.error("{}: provider failed: {}", providerId, e.getMessage());
            }
        }

        return List.copyOf(allJobs.values());
    }
}
