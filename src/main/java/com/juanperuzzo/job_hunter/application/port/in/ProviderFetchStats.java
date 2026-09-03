package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Per-provider fetch statistics for observability (email-enrichment spec, Scenario 17).
 *
 * @param source            the provider/source id (e.g. "gupy", "infojobs", "linkedin")
 * @param fetched           number of raw jobs produced before normalization
 * @param saved             number of jobs actually persisted (after dedup)
 * @param withEmail         subset of {@code saved} with a non-null contactEmail
 * @param detailFailedCount number of jobs that fell back to a snippet after a detail-fetch failure
 * @param detailSkippedCount number of jobs that kept their card snippet because their detail fetch was skipped (e.g. beyond the cap)
 * @param error             null on success, otherwise the exception message (truncated to 300 chars)
 */
public record ProviderFetchStats(
        String source,
        int fetched,
        int saved,
        int withEmail,
        int detailFailedCount,
        int detailSkippedCount,
        String error
) {}
