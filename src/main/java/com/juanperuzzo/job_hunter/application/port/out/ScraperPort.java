package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;
import java.util.function.Predicate;

public interface ScraperPort {

    /**
     * Fetches, enriches and normalizes jobs from every registered provider.
     *
     * @param keywordOverride per-user runtime keywords (already resolved)
     * @param isKnownUrl      predicate telling whether a job URL is already persisted;
     *                        enrichment sites skip their detail HTTP call when it reports
     *                        {@code true}, still returning the basic (unenriched) job
     */
    ScraperResult fetch(List<String> keywordOverride, Predicate<String> isKnownUrl);

    /** Backwards-compatible overload: nothing is considered known. */
    default ScraperResult fetch(List<String> keywordOverride) {
        return fetch(keywordOverride, url -> false);
    }

    /** Backwards-compatible overload: no keyword override and nothing known. */
    default ScraperResult fetch() {
        return fetch(List.of(), url -> false);
    }
}
