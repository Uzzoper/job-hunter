package com.juanperuzzo.job_hunter.domain.model;

import java.time.LocalDateTime;

public record EmailDraft(
        Long id,
        Long jobId,
        Long userId,
        String subject,
        String body,
        EmailStatus status,
        LocalDateTime generatedAt
) {}
