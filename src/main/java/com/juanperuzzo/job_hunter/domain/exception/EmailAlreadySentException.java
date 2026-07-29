package com.juanperuzzo.job_hunter.domain.exception;

public class EmailAlreadySentException extends RuntimeException {
    public EmailAlreadySentException(String message) {
        super(message);
    }
}
