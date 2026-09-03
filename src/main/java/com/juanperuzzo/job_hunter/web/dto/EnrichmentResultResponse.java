package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.EnrichmentResult;

/**
 * Web DTO returned by {@code POST /api/jobs/enrich-emails} (async-company-enrichment spec).
 */
public record EnrichmentResultResponse(int checked, int enriched, int skippedPortal, int failed) {

    public static EnrichmentResultResponse from(EnrichmentResult result) {
        return new EnrichmentResultResponse(
                result.checked(),
                result.enriched(),
                result.skippedPortal(),
                result.failed());
    }
}
