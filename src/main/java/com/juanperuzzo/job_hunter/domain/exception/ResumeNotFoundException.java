package com.juanperuzzo.job_hunter.domain.exception;

/** No resume PDF is stored for the requested user (never uploaded or lost on disk). */
public class ResumeNotFoundException extends RuntimeException {
    public ResumeNotFoundException(String message) {
        super(message);
    }
}
