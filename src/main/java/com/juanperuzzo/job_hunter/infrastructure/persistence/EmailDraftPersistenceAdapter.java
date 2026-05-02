package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    private EmailDraftEntity toEntity(EmailDraft draft) {
        EmailDraftEntity entity = new EmailDraftEntity(
                draft.id(),
                draft.jobId(),
                draft.subject(),
                draft.body(),
                draft.status().name()
        );
        entity.setGeneratedAt(LocalDateTime.now());
        return entity;
    }

    private EmailDraft toDomain(EmailDraftEntity entity) {
        return new EmailDraft(
                entity.getId(),
                entity.getJobId(),
                entity.getSubject(),
                entity.getBody(),
                EmailStatus.valueOf(entity.getStatus()),
                entity.getGeneratedAt()
        );
    }
}
