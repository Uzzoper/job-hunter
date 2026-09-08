package com.juanperuzzo.job_hunter.web.dto;

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
    EmailStatus draftStatus
) {}
