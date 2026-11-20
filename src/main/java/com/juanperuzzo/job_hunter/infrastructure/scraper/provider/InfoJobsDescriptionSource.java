package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.application.port.out.JobDescriptionSourcePort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Outbound-port adapter over {@link InfoJobsProvider#fetchDetailDescription(String)}
 * ({@code docs/specs/infojobs-description-backfill.md}). The fetch path and the
 * backfill share the same selector, JSON-LD fallback, rate limiter and retry.
 */
@Component
public class InfoJobsDescriptionSource implements JobDescriptionSourcePort {

    private final InfoJobsProvider provider;

    public InfoJobsDescriptionSource(InfoJobsProvider provider) {
        this.provider = provider;
    }

    @Override
    public Optional<String> fetchDescription(String url) {
        return provider.fetchDetailDescription(url);
    }
}
