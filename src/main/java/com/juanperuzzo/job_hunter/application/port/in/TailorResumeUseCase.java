package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Use case that tailors the user's resume for a specific job and returns it as PDF bytes.
 */
public interface TailorResumeUseCase {

    /**
     * Generates a tailored resume PDF for the given user and job.
     *
     * @param userId the authenticated user's ID
     * @param jobId  the job the resume is tailored for
     * @return the tailored resume as PDF bytes
     */
    byte[] tailorResume(Long userId, Long jobId);
}