package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when a manual send is attempted on an {@code EmailDraft} whose status is
 * {@code REJECTED} (an AI refusal). Such drafts are terminal and must never be
 * dispatched as a real application.
 */
public class RefusedDraftException extends RuntimeException {
    public RefusedDraftException(String message) {
        super(message);
    }

    public RefusedDraftException(String message, Throwable cause) {
        super(message, cause);
    }
}
