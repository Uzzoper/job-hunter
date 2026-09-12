package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.JobWithDraftStatus;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.ApplicationLifecycle;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class FetchJobsService implements FetchJobsUseCase, ListJobsUseCase, GetJobUseCase {

    private final ScraperPort scraperPort;
    private final JobRepository jobRepository;
    private final EmailDraftRepository emailDraftRepository;
    private final JobAnalysisRepository jobAnalysisRepository;

    public FetchJobsService(ScraperPort scraperPort, JobRepository jobRepository, EmailDraftRepository emailDraftRepository,
                            JobAnalysisRepository jobAnalysisRepository) {
        this.scraperPort = scraperPort;
        this.jobRepository = jobRepository;
        this.emailDraftRepository = emailDraftRepository;
        this.jobAnalysisRepository = jobAnalysisRepository;
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
                    stats.detailFailedCount(), stats.detailSkippedCount(), stats.error()));
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
    public List<JobWithDraftStatus> findAllWithDraftStatus(Long userId, Boolean hasEmail, Boolean excludeApplied, Integer minScore) {
        // Deliberately per-job lookups (1 draft + 1 analysis query per listed job, i.e. 2N+1).
        // At the current job-list scale this is negligible and keeps the change
        // reuse-only; a single bulk lookup replaces this if the list grows.
        var entries = findAll(hasEmail).stream()
                .map(job -> {
                    var status = draftStatus(job, userId);
                    var lifecycle = ApplicationLifecycle.fromEmailStatus(status).orElse(null);
                    return new JobWithDraftStatus(job, status, matchScore(job, userId), lifecycle);
                })
                .filter(entry -> !Boolean.TRUE.equals(excludeApplied) || entry.draftStatus() != EmailStatus.SENT)
                .toList();

        // minScore filter: drop jobs below threshold or unanalyzed (null score)
        if (minScore != null) {
            entries = entries.stream()
                    .filter(entry -> entry.matchScore() != null && entry.matchScore() >= minScore)
                    .toList();
        }

        // Sort by matchScore descending; null scores last
        return entries.stream()
                .sorted(Comparator.comparing(
                        (JobWithDraftStatus e) -> e.matchScore() != null ? e.matchScore() : Integer.MIN_VALUE)
                        .reversed())
                .toList();
    }

    private EmailStatus draftStatus(Job job, Long userId) {
        return emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
                .map(EmailDraft::status)
                .orElse(null);
    }

    private Integer matchScore(Job job, Long userId) {
        return jobAnalysisRepository.findByJobIdAndUserId(job.id(), userId)
                .map(analysis -> analysis.matchScore())
                .orElse(null);
    }

    @Override
    public Job getById(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + id));
    }
}