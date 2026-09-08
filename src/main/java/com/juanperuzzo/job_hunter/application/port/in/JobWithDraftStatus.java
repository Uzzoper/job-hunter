package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;

/**
 * A job-listing entry: a persisted {@link Job} paired with the current user's
 * draft status for it. {@code draftStatus} is {@code null} when the user has no
 * draft for the job; {@code SENT} means the user already applied to the job.
 */
public record JobWithDraftStatus(Job job, EmailStatus draftStatus) {}