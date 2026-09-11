package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.EmailStatus;

public record ExternalApplyResponse(Long jobId, EmailStatus status) {
}