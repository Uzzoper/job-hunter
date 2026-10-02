package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface JobJpaRepository extends JpaRepository<JobEntity, Long> {

    boolean existsByUrl(String url);

    List<JobEntity> findByContactEmailIsNotNull();

    List<JobEntity> findByContactEmailIsNull();

    @Query("SELECT j FROM JobEntity j WHERE j.contactEmail IS NULL AND j.companyWebsite IS NOT NULL ORDER BY j.id ASC")
    List<JobEntity> findJobsNeedingEnrichment(Pageable pageable);

    @Query("SELECT j FROM JobEntity j WHERE j.source = :source AND j.companyWebsite IS NULL ORDER BY j.id ASC")
    List<JobEntity> findBySourceAndCompanyWebsiteIsNull(String source);

    @Query("SELECT j FROM JobEntity j WHERE j.source = :source AND j.companyWebsite IS NULL AND j.id > :afterId ORDER BY j.id ASC")
    List<JobEntity> findBySourceAndCompanyWebsiteIsNullAfterId(String source, long afterId);
}
