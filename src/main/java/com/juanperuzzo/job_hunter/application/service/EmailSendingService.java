package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException;
import com.juanperuzzo.job_hunter.domain.exception.EmailDeliveryException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.MissingRecipientException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.time.LocalDateTime;

public class EmailSendingService implements SendEmailUseCase {

    private final EmailDraftRepository emailDraftRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final EmailSenderPort emailSenderPort;

    public EmailSendingService(EmailDraftRepository emailDraftRepository, JobRepository jobRepository, UserRepository userRepository, EmailSenderPort emailSenderPort) {
        this.emailDraftRepository = emailDraftRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.emailSenderPort = emailSenderPort;
    }

    @Override
    public EmailDraft send(Long userId, Long jobId) {
        var draft = emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new JobNotFoundException("Email draft not found for job " + jobId + " and user " + userId));

        if (draft.status() == EmailStatus.SENT) {
            throw new EmailAlreadySentException("Email " + draft.id() + " has already been sent");
        }

        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        var contactEmail = job.contactEmail();
        if (contactEmail == null) {
            throw new MissingRecipientException("Job " + jobId + " (" + job.url() + ") has no contact email");
        }

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        try {
            emailSenderPort.send(user.email(), contactEmail, draft.subject(), draft.body());
        } catch (RuntimeException e) {
            throw new EmailDeliveryException("Failed to send email for job " + jobId, e);
        }

        var sentDraft = new EmailDraft(
                draft.id(), draft.jobId(), draft.userId(),
                draft.subject(), draft.body(),
                EmailStatus.SENT, draft.generatedAt(), LocalDateTime.now()
        );

        return emailDraftRepository.save(sentDraft);
    }
}
