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

    /**
     * Find jobs from a source that still have no company website, ordered by
     * {@code id} ascending. Backfill candidates for {@code BackfillCompanyWebsitesUseCase}
     * (gupy-detail-domains spec §7): only null-website rows are returned, so an apply
     * can never overwrite an existing website.
     *
     * @param source source identifier (e.g. {@code gupy})
     * @return candidate jobs with a null company website
     */
    List<Job> findBySourceAndCompanyWebsiteIsNull(String source);

    /**
     * Same as {@link #findBySourceAndCompanyWebsiteIsNull(String)} but skips every row
     * with {@code id <= afterId}, still ordered by {@code id} ascending. Exists so the
     * company-website backfill can page past ranges it already covered: the resolver caps
     * at the first {@code maxHosts} distinct hosts, so a dead head (hosts whose detail
     * page yields no eligible link) would otherwise be re-examined on every round and
     * starve everything after it (gupy-detail-domains spec §7).
     *
     * <p>Rows inserted mid-backfill inside an already-covered range are not revisited by
     * the next page — the live fetch path resolves websites for new rows as they arrive.
     *
     * @param source  source identifier (e.g. {@code gupy})
     * @param afterId exclusive lower bound on {@code id}
     * @return candidate jobs with a null company website and {@code id > afterId}
     */
    List<Job> findBySourceAndCompanyWebsiteIsNullAfterId(String source, long afterId);
}
