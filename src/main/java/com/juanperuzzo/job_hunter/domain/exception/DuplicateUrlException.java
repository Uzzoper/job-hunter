package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Raised when a row with the same {@code jobs.url} UNIQUE value is already
 * persisted ({@code docs/specs/linkedin-posts-ingest.md}, 2026-11-05 round).
 * The persistence adapter converts the SQLite {@code UNIQUE constraint
 * failed: jobs.url} violation into this domain exception so the application
 * layer never inspects a Spring DAO type; the ingest pipeline surfaces it as
 * {@code IngestResult.Duplicate}.
 */
public class DuplicateUrlException extends RuntimeException {

    public DuplicateUrlException(String message) {
        super(message);
    }
}
