package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class FetchSourceJobsService implements FetchSourceJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(FetchSourceJobsService.class);
    private static final int ERROR_MESSAGE_MAX_LENGTH = 300;

    private final SourceFetchPort sourceFetchPort;
    private final JobRepository jobRepository;
    private final NormalizerPort normalizer;
    private final CompanySiteEnrichmentPort enricher;

    public FetchSourceJobsService(
            SourceFetchPort sourceFetchPort,
            JobRepository jobRepository,
            NormalizerPort normalizer) {
        this(sourceFetchPort, jobRepository, normalizer, null);
    }

    public FetchSourceJobsService(
            SourceFetchPort sourceFetchPort,
            JobRepository jobRepository,
            NormalizerPort normalizer,
            CompanySiteEnrichmentPort enricher) {
        this.sourceFetchPort = sourceFetchPort;
        this.jobRepository = jobRepository;
        this.normalizer = normalizer;
        this.enricher = enricher;
    }

    @Override
    public FetchResult fetchAndSave(String sourceId) {
        log.info("Fetching jobs from source: {}", sourceId);

        int fetched = 0;
        int saved = 0;
        int withEmail = 0;
        int detailFailedCount = 0;
        String error = null;

        try {
            var rawJobs = sourceFetchPort.fetch(sourceId);
            fetched = rawJobs.size();

            for (var raw : rawJobs) {
                if ("true".equals(raw.metadata().get("detailFailed"))) {
                    detailFailedCount++;
                }
                try {
                    var job = normalizer.normalize(raw);
                    if (job != null) {
                        // Enrichment happens after normalization, before dedup/save.
                        if (enricher != null) {
                            job = enricher.enrich(job);
                        }
                        if (!jobRepository.existsByUrl(job.url())) {
                            jobRepository.save(job);
                            saved++;
                            if (job.contactEmail() != null) {
                                withEmail++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("{}: failed to process job {}: {}", sourceId, raw.url(), e.getMessage());
                }
            }
        } catch (Exception e) {
            error = truncate(e.getMessage());
            log.error("{}: failed: {}", sourceId, e.getMessage());
        }

        var perProvider = List.of(new ProviderFetchStats(
                sourceId, fetched, saved, withEmail, detailFailedCount, error));
        log.info("{}: completed - {} saved, {} with email", sourceId, saved, withEmail);
        return new FetchResult(fetched, saved, withEmail, perProvider);
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