package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Backfills {@code companyWebsite} on stored Gupy rows that still have none,
 * using the same shared host-grouping + link-policy resolver as the fetch path
 * (gupy-detail-domains spec §7).
 *
 * <p>Counts: {@code scanned} = rows examined (stored Gupy rows with a null
 * company website in the requested range), {@code filled} = rows where a website
 * was resolved (persisted on apply, reported as a preview on dry-run),
 * {@code stillNull} = {@code scanned - filled}. Dry-run writes nothing; apply
 * persists only the null→found transitions and never overwrites an existing
 * website (the scan set is null-only by the repository query). {@code maxHosts}
 * bounds the hosts resolved per call.
 *
 * <p>{@code afterId} pages the scan by {@code id} (see the {@code run} Javadoc);
 * {@code null} means "from the start".
 */
public interface BackfillCompanyWebsitesUseCase {

    record Result(int scanned, int filled, int stillNull) {
    }

    /**
     * Run the backfill.
     *
     * <p>Without paging, a dead head starves the run: the resolver caps at the first
     * {@code maxHosts} distinct hosts of the scan, so if those hosts yield no
     * eligible link they are re-examined on every round, {@code stillNull} never
     * falls, and nothing past the head is ever reached — contradicting "repeat until
     * the counter decreases". Callers page by passing {@code afterId} (exclusive,
     * rows are ordered by {@code id}), advancing it past the range just covered.
     *
     * <p>Rows inserted mid-backfill inside an already-covered range are not revisited
     * by a later page; the live fetch path resolves websites for new rows as they
     * arrive, so nothing is permanently stranded.
     *
     * @param dryRun   {@code true} to only report what would be filled without writing
     * @param maxHosts maximum number of distinct hosts resolved per call
     * @param afterId  exclusive lower bound on row {@code id}, or {@code null} to scan from the start
     * @return scan statistics
     */
    Result run(boolean dryRun, int maxHosts, Long afterId);

    /** Convenience overload scanning from the start ({@code afterId == null}). */
    default Result run(boolean dryRun, int maxHosts) {
        return run(dryRun, maxHosts, null);
    }
}