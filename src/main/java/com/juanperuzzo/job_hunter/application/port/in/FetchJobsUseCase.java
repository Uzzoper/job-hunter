package com.juanperuzzo.job_hunter.application.port.in;

public interface FetchJobsUseCase {

    /**
     * Fetch, enrich, deduplicate and persist jobs from all providers.
     *
     * @return aggregate fetch statistics
     */
    FetchResult fetchAndSave();
}