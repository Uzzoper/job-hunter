package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.ContactEmailExtractorPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backfills missing {@code contactEmail} values from the stored title/description
 * (email-extractor-recall spec, Phase 3).
 * <p>
 * The scan set is exactly {@code findAllByContactEmailIsNull()}: existing emails are
 * never candidates, so nothing can ever overwrite them. Dry-run returns the same counts
 * as apply without persisting, giving an idempotent, rerunnable preview; on apply only
 * the null→found transitions are written (URL-based {@code Job} identity is preserved
 * by {@code withContactEmail}, which copies every other field).
 */
public class BackfillContactEmailsService implements BackfillContactEmailsUseCase {

    private static final Logger log = LoggerFactory.getLogger(BackfillContactEmailsService.class);

    private final JobRepository jobRepository;
    private final ContactEmailExtractorPort emailExtractor;

    public BackfillContactEmailsService(JobRepository jobRepository, ContactEmailExtractorPort emailExtractor) {
        this.jobRepository = jobRepository;
        this.emailExtractor = emailExtractor;
    }

    @Override
    public Result run(boolean dryRun) {
        var candidates = jobRepository.findAllByContactEmailIsNull();
        var scanned = candidates.size();
        var filled = 0;

        for (var job : candidates) {
            var found = emailExtractor.extract(job.title(), job.description());
            if (found == null) {
                continue;
            }
            if (!dryRun) {
                jobRepository.save(job.withContactEmail(found));
            }
            filled++;
        }

        var result = new Result(scanned, filled, scanned - filled);
        log.info("Contact-email backfill (dryRun={}): scanned={}, filled={}, stillNull={}",
                dryRun, result.scanned(), result.filled(), result.stillNull());
        return result;
    }
}