package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailDraftJpaRepository extends JpaRepository<EmailDraftEntity, Long> {

    Optional<EmailDraftEntity> findByJobId(Long jobId);
}
