package com.juanperuzzo.job_hunter.application.port.in;

public interface FetchSourceJobsUseCase {

    /**
     * Fetch, enrich, deduplicate and persist jobs from a single source.
     *
     * @param userId the authenticated user whose stored keywords scope the fetch
     * @param sourceId the provider/source identifier (e.g. "gupy", "infojobs", "linkedin")
     * @return aggregate fetch statistics
     */
    FetchResult fetchAndSave(Long userId, String sourceId);
}