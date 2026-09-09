package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyResult;
import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Records a portal apply (Gupy/InfoJobs, no email involved) as the canonical applied
 * record: a {@code SENT} {@code EmailDraft} marker with {@code recipientEmail = null}.
 * <p>
 * Idempotent per {@code (jobId, userId)}: a repeated call for an already-recorded
 * apply returns the existing marker without persisting anything. An existing
 * PENDING/APPROVED/REJECTED draft is superseded in place (same row id) so the
 * {@code (job_id, user_id)} unique constraint is never violated. Unknown jobs throw
 * {@link JobNotFoundException} — jobs are never upserted from this endpoint.
 */
public class RecordExternalApplyService implements RecordExternalApplyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordExternalApplyService.class);

    public static final String EXTERNAL_APPLY_SUBJECT = "Subject: [Aplicação externa]";
    public static final String EXTERNAL_APPLY_BODY = "Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado.";

    private final EmailDraftRepository emailDraftRepository;
    private final JobRepository jobRepository;

    public RecordExternalApplyService(EmailDraftRepository emailDraftRepository, JobRepository jobRepository) {
        this.emailDraftRepository = emailDraftRepository;
        this.jobRepository = jobRepository;
    }

    @Override
    public RecordExternalApplyResult record(Long userId, Long jobId) {
        jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + jobId));

        var existing = emailDraftRepository.findByJobIdAndUserId(jobId, userId);
        if (existing.isPresent() && existing.get().status() == EmailStatus.SENT) {
            log.debug("External apply already recorded for job {} and user {}", jobId, userId);
            return new RecordExternalApplyResult(existing.get(), false);
        }

        var now = LocalDateTime.now();
        var marker = new EmailDraft(
                existing.map(EmailDraft::id).orElse(null),
                jobId, userId, EXTERNAL_APPLY_SUBJECT, EXTERNAL_APPLY_BODY,
                EmailStatus.SENT, now, now);
        var saved = emailDraftRepository.save(marker);
        var created = existing.isEmpty();
        log.debug("Recorded external apply for job {} and user {} (created={})", jobId, userId, created);
        return new RecordExternalApplyResult(saved, created);
    }
}