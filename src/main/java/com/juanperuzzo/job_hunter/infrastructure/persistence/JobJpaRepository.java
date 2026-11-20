package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface JobJpaRepository extends JpaRepository<JobEntity, Long> {

    boolean existsByUrl(String url);

    List<JobEntity> findByContactEmailIsNotNull();

    List<JobEntity> findByContactEmailIsNull();

    @Query("SELECT j FROM JobEntity j WHERE j.contactEmail IS NULL AND j.companyWebsite IS NOT NULL"
            + " AND j.postedAt >= :postedSince ORDER BY j.postedAt DESC, j.id DESC")
    List<JobEntity> findJobsNeedingEnrichment(LocalDate postedSince, Pageable pageable);

    @Query("SELECT j FROM JobEntity j WHERE j.source = :source AND j.companyWebsite IS NULL ORDER BY j.id ASC")
    List<JobEntity> findBySourceAndCompanyWebsiteIsNull(String source);

    @Query("SELECT j FROM JobEntity j WHERE j.source = :source AND j.companyWebsite IS NULL AND j.id > :afterId ORDER BY j.id ASC")
    List<JobEntity> findBySourceAndCompanyWebsiteIsNullAfterId(String source, long afterId);

    /** Description backfill scan ({@code infojobs-description-backfill} spec). */
    @Query("SELECT j FROM JobEntity j WHERE j.source = :source"
            + " AND (j.description IS NULL OR LENGTH(j.description) < :maxLength)"
            + " AND j.id > :afterId ORDER BY j.id ASC")
    List<JobEntity> findBySourceWithShortDescription(String source, int maxLength, long afterId, Pageable pageable);

    /** Vacancy-level dedupe for the post ingest (amendment 2026-11-20); case-insensitive e-mail match. */
    @Query("SELECT j FROM JobEntity j WHERE LOWER(j.contactEmail) = LOWER(:contactEmail) ORDER BY j.postedAt DESC, j.id DESC")
    List<JobEntity> findByContactEmail(String contactEmail);
}
