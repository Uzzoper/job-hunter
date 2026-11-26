package com.juanperuzzo.job_hunter.domain.model;

import java.time.LocalDateTime;

public record EmailDraft(
        Long id,
        Long jobId,
        String subject,
        String body,
        EmailStatus status,
        LocalDateTime generatedAt,
        LocalDateTime sentAt,
        /** Snapshot of the recipient email (job.contactEmail) at generation/send time; nullable. */
        String recipientEmail
) {
    public EmailDraft(Long id, Long jobId, String subject, String body, EmailStatus status, LocalDateTime generatedAt) {
        this(id, jobId, subject, body, status, generatedAt, null, null);
    }

    public EmailDraft(Long id, Long jobId, String subject, String body, EmailStatus status, LocalDateTime generatedAt, LocalDateTime sentAt) {
        this(id, jobId, subject, body, status, generatedAt, sentAt, null);
    }
}
