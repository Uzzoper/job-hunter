package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;

public class FetchJobsService implements FetchJobsUseCase {

    private final ScraperPort scraperPort;
    private final JobRepository jobRepository;

    public FetchJobsService(ScraperPort scraperPort, JobRepository jobRepository) {
        this.scraperPort = scraperPort;
        this.jobRepository = jobRepository;
    }

    @Override
    public void fetchAndSave() {
        scraperPort.fetch().stream()
                .filter(job -> !jobRepository.existsByUrl(job.url()))
                .forEach(jobRepository::save);
    }
}
