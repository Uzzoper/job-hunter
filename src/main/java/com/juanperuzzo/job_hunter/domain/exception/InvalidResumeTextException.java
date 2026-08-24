package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when the resume text fails validation, for example when it is null
 * or shorter than the minimum accepted length.
 */
public class InvalidResumeTextException extends RuntimeException {

    public InvalidResumeTextException(String message) {
        super(message);
    }

    public InvalidResumeTextException(String message, Throwable cause) {
        super(message, cause);
    }
}
