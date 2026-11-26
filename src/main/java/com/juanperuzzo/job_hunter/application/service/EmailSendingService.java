package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException;
import com.juanperuzzo.job_hunter.domain.exception.EmailDeliveryException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.MissingRecipientException;
import com.juanperuzzo.job_hunter.domain.exception.RefusedDraftException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

public class EmailSendingService implements SendEmailUseCase {

    private static final Logger log = LoggerFactory.getLogger(EmailSendingService.class);

    private final EmailDraftRepository emailDraftRepository;
    private final JobRepository jobRepository;
    private final UserProfileRepository userProfileRepository;
    private final EmailSenderPort emailSenderPort;

    public EmailSendingService(EmailDraftRepository emailDraftRepository, JobRepository jobRepository, UserProfileRepository userProfileRepository, EmailSenderPort emailSenderPort) {
        this.emailDraftRepository = emailDraftRepository;
        this.jobRepository = jobRepository;
        this.userProfileRepository = userProfileRepository;
        this.emailSenderPort = emailSenderPort;
    }

    @Override
    public EmailDraft send(Long jobId) {
        var draft = emailDraftRepository.findByJobId(jobId)
                .orElseThrow(() -> new JobNotFoundException("Email draft not found for job " + jobId));

        if (draft.status() == EmailStatus.SENT) {
            throw new EmailAlreadySentException("Email " + draft.id() + " has already been sent");
        }

        if (draft.status() == EmailStatus.REJECTED) {
            throw new RefusedDraftException("Draft " + draft.id() + " for job " + jobId + " was refused and cannot be sent");
        }

        // Defense in depth at send time: a blank body is never deliverable, whatever produced it
        // (parser leniency deliberately tolerates odd AI output, and the review gate is not the
        // only entry point). Reuses the refusal exception; no fallback content is invented.
        if (draft.body() == null || draft.body().isBlank()) {
            throw new RefusedDraftException("Draft " + draft.id() + " for job " + jobId + " has a blank body and cannot be sent");
        }

        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        var contactEmail = job.contactEmail();
        if (contactEmail == null) {
            throw new MissingRecipientException("Job " + jobId + " (" + job.url() + ") has no contact email");
        }

        // Race double-check: another draft may have already delivered to this recipient
        // for the same vacancy between generation and this send. A conflicting SENT row
        // for (jobId, contactEmail) that is a different draft blocks the send.
        var alreadySent = emailDraftRepository.findSentByJobIdAndRecipientEmail(jobId, contactEmail);
        if (alreadySent.isPresent() && !alreadySent.get().id().equals(draft.id())) {
            log.debug("Skipping send, already sent to {} for job {}", contactEmail, jobId);
            throw new EmailAlreadySentException("Email already sent for job " + jobId + " to " + contactEmail);
        }


        var profile = userProfileRepository.find().orElseThrow(() -> new UserNotFoundException("Profile not found"));
        var senderEmail = profile.email() != null ? profile.email() : profile.contactEmail();
        try {
            emailSenderPort.send(senderEmail, contactEmail, draft.subject(), draft.body());
        } catch (EmailDeliveryException e) {
            // adapters already throw the domain exception — don't re-wrap
            throw e;
        } catch (RuntimeException e) {
            throw new EmailDeliveryException("Failed to send email for job " + jobId, e);
        }

        var sentDraft = new EmailDraft(
                draft.id(), draft.jobId(),
                draft.subject(), draft.body(),
                EmailStatus.SENT, draft.generatedAt(), LocalDateTime.now(), contactEmail
        );

        return emailDraftRepository.save(sentDraft);
    }
}
