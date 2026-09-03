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
}
