package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;
import java.util.function.Predicate;

/**
 * Outbound port for fetching raw job listings from a specific source/provider.
 */
public interface SourceFetchPort {

    /**
     * Fetches raw job listings from a single source.
     *
     * @param sourceId        provider/source identifier
     * @param keywordOverride per-user runtime keywords (already resolved)
     * @param isKnownUrl      predicate telling whether a job URL is already persisted;
     *                        enrichment sites skip their detail HTTP call when it reports
     *                        {@code true}, still returning the basic (unenriched) job
     */
    List<RawJob> fetch(String sourceId, List<String> keywordOverride, Predicate<String> isKnownUrl);

    /** Backwards-compatible overload: nothing is considered known. */
    default List<RawJob> fetch(String sourceId, List<String> keywordOverride) {
        return fetch(sourceId, keywordOverride, url -> false);
    }

    /** Backwards-compatible overload: no keyword override and nothing known. */
    default List<RawJob> fetch(String sourceId) {
        return fetch(sourceId, List.of(), url -> false);
    }
}
