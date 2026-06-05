package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface JobAnalysisJpaRepository extends JpaRepository<JobAnalysisEntity, Long> {
    Optional<JobAnalysisEntity> findByJobIdAndUserId(Long jobId, Long userId);
}
