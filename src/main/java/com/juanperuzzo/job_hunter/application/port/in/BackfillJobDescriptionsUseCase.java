package com.juanperuzzo.job_hunter.application.port.in;

import java.util.List;

/**
 * Repairs stored job descriptions from the source detail page
 * ({@code docs/specs/infojobs-description-backfill.md}).
 *
 * <p>The fetch path enriches only the first N cards of a run and deduplicates
 * known URLs away from later runs, so a row born with a list-card excerpt keeps
 * it forever. This use case drains that backlog without re-scraping the source.
 */
public interface BackfillJobDescriptionsUseCase {

    /**
     * @param dryRun              count the updates without persisting them
     * @param limit               candidates scanned in this run; also the run budget,
     *                            because the detail bucket allows one request per second
     * @param maxDescriptionChars a row is a candidate only while its description is
     *                            shorter than this
     * @param afterId             exclusive lower bound on job id; {@code null} scans
     *                            from the start. Page with the {@link Result#lastId()}
     *                            of the previous run.
     */
    Result run(boolean dryRun, int limit, int maxDescriptionChars, Long afterId);

    /**
     * @param scanned    candidate rows inspected in this run
     * @param updated    rows whose description was replaced (counted in dry-run too)
     * @param stillShort candidates the source could not improve
     * @param lastId     id of the last candidate scanned, {@code 0} when none —
     *                   pass it as {@code afterId} to continue
     */
    record Result(int scanned, int updated, int stillShort, long lastId) {
    }
}
