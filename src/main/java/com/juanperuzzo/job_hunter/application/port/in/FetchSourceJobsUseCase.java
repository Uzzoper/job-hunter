package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;

import java.util.function.Consumer;

public interface FetchSourceJobsUseCase {

    /**
     * Fetch, enrich, deduplicate and persist jobs from a single source.
     *
     * @param sourceId the provider/source identifier (e.g. "gupy", "infojobs", "linkedin")
     * @return aggregate fetch statistics
     */
    FetchResult fetchAndSave(String sourceId);

    /**
     * Listener-aware variant (issue #116): receives one consumer called with a
     * {@link KeywordRun} per search keyword executed by the source's scraper.
     * Default ignores the listener and keeps the synchronous behavior — the
     * async LinkedIn path overrides this to surface per-keyword status.
     */
    default FetchResult fetchAndSave(String sourceId, Consumer<KeywordRun> keywordListener) {
        return fetchAndSave(sourceId);
    }
}