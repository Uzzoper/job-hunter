package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when a manual send is attempted on an {@code EmailDraft} that must never be
 * dispatched: one whose status is {@code REJECTED} (an AI refusal, terminal), or one
 * whose body is {@code null} or blank. The blank-body case is a defense-in-depth guard
 * at send time — parse leniency is deliberate, so a draft that carries no deliverable
 * body is refused here instead of being mailed to a recruiter.
 */
public class RefusedDraftException extends RuntimeException {
    public RefusedDraftException(String message) {
        super(message);
    }

    public RefusedDraftException(String message, Throwable cause) {
        super(message, cause);
    }
}