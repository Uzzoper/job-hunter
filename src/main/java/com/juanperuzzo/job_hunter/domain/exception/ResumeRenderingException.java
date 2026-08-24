package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when a resume artifact cannot be produced, for example when the
 * bundled HTML template or font is missing from the classpath or the PDF
 * rendering step fails.
 */
public class ResumeRenderingException extends RuntimeException {

    public ResumeRenderingException(String message) {
        super(message);
    }

    public ResumeRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
