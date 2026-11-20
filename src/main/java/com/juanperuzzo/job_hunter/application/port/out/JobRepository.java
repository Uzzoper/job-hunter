package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JobRepository {

    boolean existsByUrl(String url);

    Job save(Job job);

    List<Job> findAll();

    List<Job> findAllByContactEmailIsNotNull();

    List<Job> findAllByContactEmailIsNull();

    /**
     * Rows of {@code source} whose description is missing or shorter than
     * {@code maxLength}, ordered by {@code id} ascending, at most {@code limit},
     * with {@code id > afterId} ({@code docs/specs/infojobs-description-backfill.md}).
     * Rows with an already-good description are never candidates, so a backfill
     * cannot overwrite one.
     */
    List<Job> findBySourceWithShortDescription(String source, int maxLength, long afterId, int limit);

    /**
     * Rows whose contact e-mail equals {@code contactEmail}, case-insensitively.
     * Used by the LinkedIn-post ingest for vacancy-level dedupe (amendment
     * 2026-11-20): the same opening re-shared by another author gets a new
     * permalink, so URL identity alone cannot see the duplicate. The 30-day
     * window is applied by the caller, not here.
     */
    List<Job> findByContactEmail(String contactEmail);

    Optional<Job> findById(Long id);

    /**
     * Find jobs that still need company-site enrichment: no contact email yet, a
     * company website present, and posted on or after {@code postedSince}, newest
     * first ({@code postedAt} desc, {@code id} desc as tiebreak) and bounded by
     * {@code limit}. The date window keeps the batch on still-applicable rows —
     * without it the stale tail (98% of the queue in the 2026-11-19 measurement)
     * consumes the whole budget and starves recent listings.
     *
     * @param limit       maximum number of candidates to return
     * @param postedSince inclusive lower bound on {@code postedAt}
     * @return candidate jobs needing enrichment, newest first
     */
    List<Job> findJobsNeedingEnrichment(int limit, LocalDate postedSince);

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
