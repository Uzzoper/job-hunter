package com.juanperuzzo.job_hunter.domain.exception;

/**
 * The digest echoed at commit does not match the uploaded file's digest
 * (issue #92 slice b): the artifact being confirmed is not the one that was
 * previewed. Mapped to {@code 409 Conflict} — nothing is written in this path.
 */
public class ResumeDigestMismatchException extends RuntimeException {
    public ResumeDigestMismatchException(String message) {
        super(message);
    }
}
