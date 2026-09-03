package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;

import java.util.List;

/**
 * Web DTO returned by {@code POST /api/jobs/fetch} (email-enrichment spec, Scenario 17).
 */
public record FetchResultResponse(
        int totalFetched,
        int totalSaved,
        int totalWithEmail,
        List<ProviderFetchStatsResponse> perProvider
) {
    public static FetchResultResponse from(FetchResult result) {
        return new FetchResultResponse(
                result.totalFetched(),
                result.totalSaved(),
                result.totalWithEmail(),
                result.perProvider().stream()
                        .map(ProviderFetchStatsResponse::from)
                        .toList());
    }
}