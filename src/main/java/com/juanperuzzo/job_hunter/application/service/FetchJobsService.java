package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class FetchJobsService implements FetchJobsUseCase, ListJobsUseCase, GetJobUseCase {

    private final ScraperPort scraperPort;
    private final JobRepository jobRepository;

    public FetchJobsService(ScraperPort scraperPort, JobRepository jobRepository) {
        this.scraperPort = scraperPort;
        this.jobRepository = jobRepository;
    }

    @Override
    public FetchResult fetchAndSave() {
        var result = scraperPort.fetch();
        var jobs = result.jobs();

        int totalSaved = 0;
        var savedBySource = new HashMap<String, Integer>();
        var withEmailBySource = new HashMap<String, Integer>();

        for (var job : jobs) {
            if (jobRepository.existsByUrl(job.url())) {
                continue;
            }
            jobRepository.save(job);
            totalSaved++;
            savedBySource.merge(job.source(), 1, Integer::sum);
            if (job.contactEmail() != null) {
                withEmailBySource.merge(job.source(), 1, Integer::sum);
            }
        }

        int totalFetched = 0;
        int totalWithEmail = 0;
        var perProvider = new ArrayList<ProviderFetchStats>(result.perProvider().size());
        for (var stats : result.perProvider()) {
            totalFetched += stats.fetched();
            var saved = savedBySource.getOrDefault(stats.source(), 0);
            var withEmail = withEmailBySource.getOrDefault(stats.source(), 0);
            totalWithEmail += withEmail;
            perProvider.add(new ProviderFetchStats(
                    stats.source(), stats.fetched(), saved, withEmail,
                    stats.detailFailedCount(), stats.error()));
        }

        return new FetchResult(totalFetched, totalSaved, totalWithEmail, perProvider);
    }

    @Override
    public List<Job> findAll() {
        return jobRepository.findAll();
    }

    @Override
    public List<Job> findAll(Boolean hasEmail) {
        if (hasEmail == null) {
            return jobRepository.findAll();
        }
        return hasEmail
                ? jobRepository.findAllByContactEmailIsNotNull()
                : jobRepository.findAllByContactEmailIsNull();
    }

    @Override
    public Job getById(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + id));
    }
}