package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Records that the owner applied directly on the portal (Gupy/InfoJobs) for a job,
 * marking it as the canonical applied record without any email being generated or sent.
 */
public interface RecordExternalApplyUseCase {

    RecordExternalApplyResult record(Long userId, Long jobId);
}