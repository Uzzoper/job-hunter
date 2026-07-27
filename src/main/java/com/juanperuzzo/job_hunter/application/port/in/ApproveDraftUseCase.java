package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EmailDraft;

public interface ApproveDraftUseCase {
    EmailDraft approve(Long userId, Long jobId);
}
