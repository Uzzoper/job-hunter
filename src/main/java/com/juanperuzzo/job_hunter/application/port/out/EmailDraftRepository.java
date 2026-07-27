package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailDraftRepository {

    EmailDraft save(EmailDraft draft);

    Optional<EmailDraft> findByJobId(Long jobId);

    Optional<EmailDraft> findByJobIdAndUserId(Long jobId, Long userId);

    List<EmailDraft> findByUserIdAndStatusIn(Long userId, List<EmailStatus> statuses);

    List<EmailDraft> findAllByStatusIn(List<EmailStatus> statuses);

    long countByUserIdAndStatusAndSentAtAfter(Long userId, EmailStatus status, LocalDateTime after);
}
