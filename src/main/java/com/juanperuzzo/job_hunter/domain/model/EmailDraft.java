package com.juanperuzzo.job_hunter.domain.model;

import java.time.LocalDateTime;

public record EmailDraft(
        Long id,
        Long jobId,
        String subject,
        String body,
        EmailStatus status,
        LocalDateTime generatedAt
) {}
