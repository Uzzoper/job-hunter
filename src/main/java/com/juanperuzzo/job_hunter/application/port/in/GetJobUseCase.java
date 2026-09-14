package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

public interface GetJobUseCase {
    Job getById(Long id);

    /**
     * Returns the job paired with the current user's draft status, AI match score
     * and application lifecycle — the same enrichment the list endpoint uses.
     */
    JobWithDraftStatus getByIdWithDraftStatus(Long userId, Long jobId);
}
