package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EmailDraft;

public interface GenerateEmailUseCase {
    EmailDraft generate(Long userId, Long jobId);
}
