package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;

/**
 * Outbound port for fetching raw job listings from a specific source/provider.
 * <p>
 * Implementations locate the appropriate provider by source ID, extract raw job
 * data, and return it for further processing (normalization, deduplication, persistence).
 */
public interface SourceFetchPort {

    /**
     * Fetch raw job listings from the given source.
     *
     * @param sourceId the provider/source identifier (e.g. "gupy", "infojobs")
     * @return list of raw jobs extracted from the source
     * @throws IllegalArgumentException if the source is unknown
     */
    List<RawJob> fetch(String sourceId);
}
