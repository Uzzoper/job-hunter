package com.juanperuzzo.job_hunter.application.port.in;

public interface FetchJobsUseCase {

    /**
     * Fetch, enrich, deduplicate and persist jobs from all providers.
     *
     * @param userId the authenticated user whose stored keywords scope the fetch
     * @return aggregate fetch statistics
     */
    FetchResult fetchAndSave(Long userId);
}