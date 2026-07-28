package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.service.ApproveDraftService;
import com.juanperuzzo.job_hunter.domain.exception.DraftAlreadyApprovedException;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApproveDraftServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long JOB_ID = 2L;
    private static final Long DRAFT_ID = 10L;

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @InjectMocks
    private ApproveDraftService approveDraftService;

    @Test
    @DisplayName("should approve a PENDING draft and return APPROVED")
    void approve_whenPending_shouldReturnApproved() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now(), null);

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(emailDraftRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = approveDraftService.approve(USER_ID, JOB_ID);

        assertEquals(EmailStatus.APPROVED, result.status());
        verify(emailDraftRepository).save(any());
    }

    @Test
    @DisplayName("should throw EmailAlreadySentException when draft is SENT")
    void approve_whenAlreadySent_shouldThrow() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));

        assertThrows(EmailAlreadySentException.class, () -> approveDraftService.approve(USER_ID, JOB_ID));
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw DraftAlreadyApprovedException when already APPROVED")
    void approve_whenAlreadyApproved_shouldThrow() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.APPROVED, LocalDateTime.now(), null);

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));

        assertThrows(DraftAlreadyApprovedException.class, () -> approveDraftService.approve(USER_ID, JOB_ID));
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when draft not found")
    void approve_whenNoDraft_shouldThrow() {
        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> approveDraftService.approve(USER_ID, JOB_ID));
        verify(emailDraftRepository, never()).save(any());
    }
}
