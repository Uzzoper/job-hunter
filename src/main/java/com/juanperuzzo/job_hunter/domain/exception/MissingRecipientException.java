package com.juanperuzzo.job_hunter.domain.exception;

public class MissingRecipientException extends RuntimeException {
    public MissingRecipientException(String message) {
        super(message);
    }
}
