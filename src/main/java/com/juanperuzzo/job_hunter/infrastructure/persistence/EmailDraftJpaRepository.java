package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailDraftJpaRepository extends JpaRepository<EmailDraftEntity, Long> {

    Optional<EmailDraftEntity> findByJobId(Long jobId);

    Optional<EmailDraftEntity> findByJobIdAndUserId(Long jobId, Long userId);

    List<EmailDraftEntity> findByUserIdAndStatusIn(Long userId, List<String> statuses);

    List<EmailDraftEntity> findByStatusIn(List<String> statuses);

    Optional<EmailDraftEntity> findByJobIdAndRecipientEmailAndStatusIn(Long jobId, String recipientEmail, List<String> statuses);

    long countByUserIdAndStatusAndSentAtAfter(Long userId, String status, LocalDateTime after);
}
