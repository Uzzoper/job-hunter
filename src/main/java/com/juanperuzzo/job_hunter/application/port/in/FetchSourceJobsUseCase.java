package com.juanperuzzo.job_hunter.application.port.in;

public interface FetchSourceJobsUseCase {

    /**
     * Fetch, enrich, deduplicate and persist jobs from a single source.
     *
     * @param sourceId the provider/source identifier (e.g. "gupy", "infojobs", "linkedin")
     * @return aggregate fetch statistics
     */
    FetchResult fetchAndSave(String sourceId);
}