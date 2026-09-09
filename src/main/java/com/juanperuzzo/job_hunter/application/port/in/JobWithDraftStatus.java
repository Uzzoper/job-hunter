package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;

/**
 * A job-listing entry: a persisted {@link Job} paired with the current user's
 * draft status and AI match score for it. {@code draftStatus} is {@code null}
 * when the user has no draft for the job; {@code SENT} means the user already
 * applied to the job. {@code matchScore} is {@code null} when the user has not
 * analyzed the job; 0–100 otherwise. Results are sorted by matchScore descending
 * with null scores last.
 */
public record JobWithDraftStatus(Job job, EmailStatus draftStatus, Integer matchScore) {}