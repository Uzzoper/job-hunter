package com.juanperuzzo.job_hunter.domain.exception;

public class ProfileNotConfiguredException extends RuntimeException {
    public ProfileNotConfiguredException(String message) {
        super(message);
    }
}
