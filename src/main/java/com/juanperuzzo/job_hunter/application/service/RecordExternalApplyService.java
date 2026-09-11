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
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

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

    /**
     * Bounded write retries for transient lock contention (e.g. SQLite {@code SQLITE_BUSY}
     * under parallel writers). Constraint violations are definitive and never retried —
     * they resolve via re-read instead.
     */
    private static final int MAX_WRITE_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MILLIS = 25;

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
        boolean conflicted = false;
        EmailDraft saved;
        try {
            saved = saveWithBusyRetry(marker);
        } catch (RuntimeException writeFailure) {
            if (!isUniqueConflict(writeFailure)) {
                throw writeFailure;
            }
            // Lost a concurrent insert race: the unique constraint is the source of
            // truth — return the winner instead of surfacing a 409. No in-process
            // locking (it would silently break under multiple instances).
            log.debug("Concurrent apply record for job {} and user {} — returning existing", jobId, userId);
            conflicted = true;
            saved = emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                    .orElseThrow(() -> writeFailure);
        }
        var created = existing.isEmpty() && !conflicted;
        log.debug("Recorded external apply for job {} and user {} (created={})", jobId, userId, created);
        return new RecordExternalApplyResult(saved, created);
    }

    /**
     * Persists the marker, retrying transient lock contention with linear backoff.
     * Anything else propagates to the caller for conflict dispatch — constraint
     * violations surface in different wrappers depending on when the flush happens
     * (e.g. {@code DataIntegrityViolationException} on direct save,
     * {@code JpaSystemException} on commit-time flush), so only lock failures
     * are recognized here.
     */
    private EmailDraft saveWithBusyRetry(EmailDraft marker) {
        int attempt = 0;
        while (true) {
            try {
                return emailDraftRepository.save(marker);
            } catch (ConcurrencyFailureException busy) {
                if (++attempt >= MAX_WRITE_ATTEMPTS) {
                    throw busy;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
        }
    }

    /**
     * Detects a unique-constraint conflict by walking the cause chain instead of
     * matching a single wrapper type: the same SQLite {@code UNIQUE constraint
     * failed} surfaces as {@code DataIntegrityViolationException} or as a JPA
     * exception with the driver message nested inside. Matching stays on Spring
     * types and message text — never on driver classes, which must not leak into
     * the application layer.
     */
    private static boolean isUniqueConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            var message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("unique constraint")) {
                return true;
            }
        }
        return false;
    }
}