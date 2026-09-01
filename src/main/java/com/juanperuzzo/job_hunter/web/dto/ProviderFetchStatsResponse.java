package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;

/**
 * Web DTO for per-provider fetch statistics (email-enrichment spec, Scenario 17).
 */
public record ProviderFetchStatsResponse(
        String source,
        int fetched,
        int saved,
        int withEmail,
        int detailFailedCount,
        String error
) {
    public static ProviderFetchStatsResponse from(ProviderFetchStats stats) {
        return new ProviderFetchStatsResponse(
                stats.source(),
                stats.fetched(),
                stats.saved(),
                stats.withEmail(),
                stats.detailFailedCount(),
                stats.error());
    }
}