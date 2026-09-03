package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;
import java.util.Optional;

public interface JobRepository {

    boolean existsByUrl(String url);

    Job save(Job job);

    List<Job> findAll();

    List<Job> findAllByContactEmailIsNotNull();

    List<Job> findAllByContactEmailIsNull();

    Optional<Job> findById(Long id);

    /**
     * Find jobs that still need company-site enrichment: no contact email yet but a
     * company website is present, ordered by {@code id} ascending and bounded by
     * {@code limit}. Used by {@code CompanyEnrichmentService}.
     *
     * @param limit maximum number of candidates to return
     * @return candidate jobs needing enrichment
     */
    List<Job> findJobsNeedingEnrichment(int limit);
}
