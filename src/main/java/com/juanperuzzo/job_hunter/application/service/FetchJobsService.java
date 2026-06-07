package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public class FetchJobsService implements FetchJobsUseCase, ListJobsUseCase, GetJobUseCase {

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

    @Override
    public List<Job> findAll() {
        return jobRepository.findAll();
    }

    @Override
    public Job getById(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + id));
    }
}
