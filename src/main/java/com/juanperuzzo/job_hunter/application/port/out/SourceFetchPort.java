package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;

/**
 * Outbound port for fetching raw job listings from a specific source/provider.
 */
public interface SourceFetchPort {

    /**
     * Fetch raw job listings from the given source.
     */
    List<RawJob> fetch(String sourceId, List<String> keywordOverride);

    default List<RawJob> fetch(String sourceId) {
        return fetch(sourceId, List.of());
    }
}
