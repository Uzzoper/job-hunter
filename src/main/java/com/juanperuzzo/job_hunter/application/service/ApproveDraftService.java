package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.ApproveDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.exception.DraftAlreadyApprovedException;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;

public class ApproveDraftService implements ApproveDraftUseCase {

    private final EmailDraftRepository emailDraftRepository;

    public ApproveDraftService(EmailDraftRepository emailDraftRepository) {
        this.emailDraftRepository = emailDraftRepository;
    }

    @Override
    public EmailDraft approve(Long userId, Long jobId) {
        var draft = emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new JobNotFoundException("Email draft not found for job " + jobId + " and user " + userId));

        if (draft.status() == EmailStatus.SENT) {
            throw new EmailAlreadySentException("Email " + draft.id() + " has already been sent");
        }
        if (draft.status() == EmailStatus.APPROVED) {
            throw new DraftAlreadyApprovedException("Email " + draft.id() + " is already approved");
        }

        var approved = new EmailDraft(
                draft.id(), draft.jobId(), draft.userId(),
                draft.subject(), draft.body(),
                EmailStatus.APPROVED, draft.generatedAt(), draft.sentAt()
        );

        return emailDraftRepository.save(approved);
    }
}
