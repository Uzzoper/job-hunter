package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.BackfillJobDescriptionsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.JobDescriptionSourcePort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Repairs stored InfoJobs descriptions from their detail page
 * ({@code docs/specs/infojobs-description-backfill.md}). Mirrors
 * {@link BackfillCompanyWebsitesService}: a query-defined scan set, an
 * {@code afterId} cursor for paging, and dry-run semantics that report the
 * same counts as apply without persisting.
 */
public class BackfillJobDescriptionsService implements BackfillJobDescriptionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(BackfillJobDescriptionsService.class);
    private static final String SOURCE = "infojobs";
    private static final int MAX_LIMIT = 200;

    private final JobRepository jobRepository;
    private final JobDescriptionSourcePort descriptionSource;

    public BackfillJobDescriptionsService(
            JobRepository jobRepository,
            JobDescriptionSourcePort descriptionSource) {
        this.jobRepository = jobRepository;
        this.descriptionSource = descriptionSource;
    }

    @Override
    public Result run(boolean dryRun, int limit, int maxDescriptionChars, Long afterId) {
        if (limit <= 0) {
            return new Result(0, 0, 0, 0L);
        }
        var effectiveLimit = Math.min(limit, MAX_LIMIT);
        var effectiveMinLength = Math.max(maxDescriptionChars, 0);
        var cursor = afterId == null ? 0L : afterId;

        List<Job> candidates = jobRepository.findBySourceWithShortDescription(
                SOURCE, effectiveMinLength, cursor, effectiveLimit);

        if (candidates.isEmpty()) {
            return new Result(0, 0, 0, 0L);
        }

        var updated = 0;
        var stillShort = 0;
        long lastId = 0;
        for (var job : candidates) {
            lastId = job.id() == null ? lastId : job.id();
            Optional<String> fetched;
            try {
                fetched = descriptionSource.fetchDescription(job.url());
            } catch (RuntimeException e) {
                log.debug("backfill-descriptions: source failed for {}: {}", job.url(), e.getMessage());
                stillShort++;
                continue;
            }
            if (fetched.isEmpty() || fetched.get().length() <= currentLength(job.description())) {
                stillShort++;
                continue;
            }
            if (!dryRun) {
                jobRepository.save(job.withDescription(fetched.get()));
            }
            updated++;
        }

        log.info("Description backfill (dryRun={}, limit={}, maxDescriptionChars={}, afterId={}):"
                        + " scanned={}, updated={}, stillShort={}, lastId={}",
                dryRun, effectiveLimit, effectiveMinLength, cursor,
                candidates.size(), updated, stillShort, lastId);
        return new Result(candidates.size(), updated, stillShort, lastId);
    }

    private static int currentLength(String description) {
        return description == null ? 0 : description.length();
    }
}
