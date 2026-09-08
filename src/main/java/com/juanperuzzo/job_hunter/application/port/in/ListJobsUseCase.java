package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public interface ListJobsUseCase {
    List<Job> findAll();

    List<Job> findAll(Boolean hasEmail);

    /**
     * Lists jobs for a user, each entry carrying the user's draft status for the
     * job. When {@code excludeApplied} is {@code true}, entries whose
     * {@code draftStatus} is {@code SENT} (the user already applied) are dropped.
     */
    List<JobWithDraftStatus> findAllWithDraftStatus(Long userId, Boolean hasEmail, Boolean excludeApplied);
}
