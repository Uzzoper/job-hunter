package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public interface ListJobsUseCase {
    List<Job> findAll();

    List<Job> findAll(Boolean hasEmail);

    /**
     * Lists jobs for a user, each entry carrying the user's draft status and AI
     * match score for the job. When {@code excludeApplied} is {@code true},
     * entries whose {@code draftStatus} is {@code SENT} (the user already
     * applied) are dropped. When {@code minScore} is specified, entries whose
     * {@code matchScore} is {@code null} or below the threshold are dropped.
     * Results are sorted by matchScore descending (null scores last).
     */
    List<JobWithDraftStatus> findAllWithDraftStatus(Long userId, Boolean hasEmail, Boolean excludeApplied, Integer minScore);
}
