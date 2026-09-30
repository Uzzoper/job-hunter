package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Backfills {@code companyWebsite} on stored Gupy rows that still have none,
 * using the same shared host-grouping + link-policy resolver as the fetch path
 * (gupy-detail-domains spec §7).
 *
 * <p>Counts: {@code scanned} = rows examined (all stored Gupy rows with a null
 * company website), {@code filled} = rows where a website was resolved
 * (persisted on apply, reported as a preview on dry-run), {@code stillNull} =
 * {@code scanned - filled}. Dry-run writes nothing; apply persists only the
 * null→found transitions and never overwrites an existing website (the scan set
 * is null-only by the repository query). {@code maxHosts} bounds the hosts
 * resolved per call — repeat the call until {@code stillNull} stops shrinking.
 */
public interface BackfillCompanyWebsitesUseCase {

    record Result(int scanned, int filled, int stillNull) {
    }

    /**
     * Run the backfill.
     *
     * @param dryRun   {@code true} to only report what would be filled without writing
     * @param maxHosts maximum number of distinct hosts resolved per call
     * @return scan statistics
     */
    Result run(boolean dryRun, int maxHosts);
}