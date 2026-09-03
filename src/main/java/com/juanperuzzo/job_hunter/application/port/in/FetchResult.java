package com.juanperuzzo.job_hunter.application.port.in;

import java.util.List;

/**
 * Aggregate fetch result returned by {@code FetchJobsService.fetchAndSave()} and
 * {@code FetchSourceJobsService.fetchAndSave(String)} (email-enrichment spec, Scenario 17).
 *
 * @param totalFetched  total raw jobs fetched across all providers
 * @param totalSaved    total jobs persisted across all providers
 * @param totalWithEmail number of persisted jobs with a non-null contactEmail (after enrichment)
 * @param perProvider   per-provider fetch statistics
 */
public record FetchResult(
        int totalFetched,
        int totalSaved,
        int totalWithEmail,
        List<ProviderFetchStats> perProvider
) {}
