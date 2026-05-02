package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.EmailDraft;

import java.util.Optional;

public interface EmailDraftRepository {

    EmailDraft save(EmailDraft draft);

    Optional<EmailDraft> findByJobId(Long jobId);
}
