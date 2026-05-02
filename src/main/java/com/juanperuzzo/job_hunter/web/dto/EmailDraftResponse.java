package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import java.time.LocalDateTime;

public record EmailDraftResponse(
    Long id,
    Long jobId,
    String subject,
    String body,
    EmailStatus status,
    LocalDateTime generatedAt
) {}
