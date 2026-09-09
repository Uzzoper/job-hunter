package com.juanperuzzo.job_hunter.domain.model;

/**
 * User work-model preference: remote, hybrid, or on-site.
 * Nullable within {@link UserPreferences} — null means unset.
 */
public enum WorkModel {
    REMOTE,
    HYBRID,
    ONSITE
}
