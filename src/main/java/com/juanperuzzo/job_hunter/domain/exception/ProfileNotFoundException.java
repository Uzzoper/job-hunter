package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when an email or resume personalization is requested for a user who
 * has no {@link com.juanperuzzo.job_hunter.domain.model.UserProfile} — a profile
 * is required to personalize content, so a silent generic output is an error.
 */
public class ProfileNotFoundException extends RuntimeException {
    public ProfileNotFoundException(String message) {
        super(message);
    }
}