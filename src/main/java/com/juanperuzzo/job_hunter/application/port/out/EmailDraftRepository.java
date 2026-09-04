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

    /**
     * Returns the {@code SENT} draft for the given job and recipient email, or empty
     * if no sent application exists for that (jobId, recipientEmail) pair. Used to
     * enforce idempotency — the sender must never be contacted twice for a pair.
     */
    Optional<EmailDraft> findSentByJobIdAndRecipientEmail(Long jobId, String recipientEmail);

    long countByUserIdAndStatusAndSentAtAfter(Long userId, EmailStatus status, LocalDateTime after);
}
