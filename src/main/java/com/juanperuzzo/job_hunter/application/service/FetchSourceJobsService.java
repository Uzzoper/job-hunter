package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FetchSourceJobsService implements FetchSourceJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(FetchSourceJobsService.class);

    private final SourceFetchPort sourceFetchPort;
    private final JobRepository jobRepository;
    private final NormalizerPort normalizer;

    public FetchSourceJobsService(SourceFetchPort sourceFetchPort, JobRepository jobRepository, NormalizerPort normalizer) {
        this.sourceFetchPort = sourceFetchPort;
        this.jobRepository = jobRepository;
        this.normalizer = normalizer;
    }

    @Override
    public void fetchAndSave(String sourceId) {
        log.info("Fetching jobs from source: {}", sourceId);

        var rawJobs = sourceFetchPort.fetch(sourceId);
        int saved = 0;
        int skipped = 0;

        for (var raw : rawJobs) {
            try {
                var job = normalizer.normalize(raw);
                if (job != null && !jobRepository.existsByUrl(job.url())) {
                    jobRepository.save(job);
                    saved++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("{}: failed to process job {}: {}", sourceId, raw.url(), e.getMessage());
            }
        }

        log.info("{}: completed - {} saved, {} skipped/duplicates", sourceId, saved, skipped);
    }
}
