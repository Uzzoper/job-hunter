package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EmailDraftPersistenceAdapter implements EmailDraftRepository {

    private final EmailDraftJpaRepository jpaRepository;

    public EmailDraftPersistenceAdapter(EmailDraftJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public EmailDraft save(EmailDraft draft) {
        EmailDraftEntity entity = toEntity(draft);
        EmailDraftEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<EmailDraft> findByJobId(Long jobId) {
        return jpaRepository.findByJobId(jobId).map(this::toDomain);
    }

    @Override
    public Optional<EmailDraft> findByJobIdAndUserId(Long jobId, Long userId) {
        return jpaRepository.findByJobIdAndUserId(jobId, userId).map(this::toDomain);
    }

    @Override
    public List<EmailDraft> findByUserIdAndStatusIn(Long userId, List<EmailStatus> statuses) {
        var statusNames = statuses.stream().map(Enum::name).toList();
        return jpaRepository.findByUserIdAndStatusIn(userId, statusNames)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserIdAndStatusAndSentAtAfter(Long userId, EmailStatus status, LocalDateTime after) {
        return jpaRepository.countByUserIdAndStatusAndSentAtAfter(userId, status.name(), after);
    }

    @Override
    public List<EmailDraft> findAllByStatusIn(List<EmailStatus> statuses) {
        var statusNames = statuses.stream().map(Enum::name).toList();
        return jpaRepository.findByStatusIn(statusNames)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private EmailDraftEntity toEntity(EmailDraft draft) {
        EmailDraftEntity entity = new EmailDraftEntity(
                draft.id(),
                draft.jobId(),
                draft.userId(),
                draft.subject(),
                draft.body(),
                draft.status().name()
        );
        entity.setGeneratedAt(draft.generatedAt());
        entity.setSentAt(draft.sentAt());
        return entity;
    }

    private EmailDraft toDomain(EmailDraftEntity entity) {
        return new EmailDraft(
                entity.getId(),
                entity.getJobId(),
                entity.getUserId(),
                entity.getSubject(),
                entity.getBody(),
                EmailStatus.valueOf(entity.getStatus()),
                entity.getGeneratedAt(),
                entity.getSentAt()
        );
    }
}
