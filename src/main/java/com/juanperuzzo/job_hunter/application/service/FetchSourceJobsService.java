package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.KeywordResolver;
import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class FetchSourceJobsService implements FetchSourceJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(FetchSourceJobsService.class);
    private static final int ERROR_MESSAGE_MAX_LENGTH = 300;
    /** Source id whose raw jobs normalize with the LinkedIn compound-keywords normalizer. */
    private static final String LINKEDIN_SOURCE_ID = "linkedin";

    private final SourceFetchPort sourceFetchPort;
    private final JobRepository jobRepository;
    private final NormalizerPort linkedinNormalizer;
    private final NormalizerPort sourceNormalizer;
    private final KeywordResolver keywordResolver;

    /**
     * Normalizer is picked per source (fase-a-sourcing-restoration Scenario 5):
     * {@code linkedinNormalizer} serves only the {@code linkedin} source — it carries
     * the LinkedIn compound keyword list; every other source (gupy, infojobs, ...)
     * normalizes with {@code sourceNormalizer} (built from {@code scraper.gupy.keywords}).
     */
    public FetchSourceJobsService(
            SourceFetchPort sourceFetchPort,
            JobRepository jobRepository,
            NormalizerPort linkedinNormalizer,
            NormalizerPort sourceNormalizer,
            KeywordResolver keywordResolver) {
        this.sourceFetchPort = sourceFetchPort;
        this.jobRepository = jobRepository;
        this.linkedinNormalizer = linkedinNormalizer;
        this.sourceNormalizer = sourceNormalizer;
        this.keywordResolver = keywordResolver;
    }

    @Override
    public FetchResult fetchAndSave(Long userId, String sourceId) {
        log.info("Fetching jobs from source: {}", sourceId);
        var keywords = List.copyOf(keywordResolver.resolve(userId));

        int fetched = 0;
        int saved = 0;
        int withEmail = 0;
        int detailFailedCount = 0;
        int detailSkippedCount = 0;
        String error = null;

        try {
            var normalizer = normalizerFor(sourceId);
            var rawJobs = sourceFetchPort.fetch(sourceId, keywords, jobRepository::existsByUrl);
            fetched = rawJobs.size();

            for (var raw : rawJobs) {
                if ("true".equals(raw.metadata().get("detailFailed"))) {
                    detailFailedCount++;
                }
                if ("true".equals(raw.metadata().get("detailSkipped"))) {
                    detailSkippedCount++;
                }
                try {
                    var job = normalizer.normalize(raw, keywords);
                    if (job != null) {
                        // NOTE (async-company-enrichment): enrichment is intentionally removed
                        // from this hot path; it runs out-of-band via CompanyEnrichmentService.
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
                sourceId, fetched, saved, withEmail, detailFailedCount, detailSkippedCount, error));
        log.info("{}: completed - {} saved, {} with email", sourceId, saved, withEmail);
        return new FetchResult(fetched, saved, withEmail, perProvider);
    }

    /**
     * Normalizer matched to the requested source: the LinkedIn compound-keywords
     * normalizer only for {@code linkedin}; the general source normalizer otherwise.
     */
    private NormalizerPort normalizerFor(String sourceId) {
        return LINKEDIN_SOURCE_ID.equals(sourceId) ? linkedinNormalizer : sourceNormalizer;
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