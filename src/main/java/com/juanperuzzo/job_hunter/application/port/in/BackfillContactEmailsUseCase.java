package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Backfills {@code contactEmail} on jobs that still have none, using the shared
 * {@code extract(title, description)} pipeline on their stored title/description.
 * <p>
 * Counts: {@code scanned} = jobs examined (all stored jobs with a null contact email),
 * {@code filled} = jobs where an email was found (persisted on apply, reported as a
 * preview on dry-run), {@code stillNull} = {@code scanned - filled}. Dry-run writes
 * nothing; apply persists only the null→found transitions and never overwrites an
 * existing email (the scan set is null-only by the repository query).
 */
public interface BackfillContactEmailsUseCase {

    record Result(int scanned, int filled, int stillNull) {
    }

    /**
     * Run the backfill.
     *
     * @param dryRun {@code true} to only report what would be filled without writing
     * @return scan statistics
     */
    Result run(boolean dryRun);
}