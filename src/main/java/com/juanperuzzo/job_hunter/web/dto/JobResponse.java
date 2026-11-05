package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.ApplicationLifecycle;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;

import java.time.LocalDate;

public record JobResponse(
    Long id,
    String title,
    String company,
    String url,
    String description,
    LocalDate postedAt,
    String source,
    String contactEmail,
    /** Current user's draft status for the job; null when there is no draft. SENT means already applied. */
    EmailStatus draftStatus,
    /** Current user's AI match score (0-100) for the job; null when not analyzed. */
    Integer matchScore,
    /** Explicit application lifecycle (issue #56); null when the job has no lifecycle yet. */
    ApplicationLifecycle lifecycleState,
    /** Contact e-mail from a free provider — flagged for review, not silently trusted (linkedin-posts-ingest spec). */
    boolean emailSuspect
) {}
