package com.juanperuzzo.job_hunter.domain.exception;

public class DraftAlreadyApprovedException extends RuntimeException {
    public DraftAlreadyApprovedException(String message) {
        super(message);
    }
}
