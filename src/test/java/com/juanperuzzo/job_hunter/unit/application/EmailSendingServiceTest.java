package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.EmailSendingService;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException;
import com.juanperuzzo.job_hunter.domain.exception.EmailDeliveryException;
import com.juanperuzzo.job_hunter.domain.exception.MissingRecipientException;
import com.juanperuzzo.job_hunter.domain.exception.RefusedDraftException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailSendingServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long JOB_ID = 2L;
    private static final Long DRAFT_ID = 10L;
    private static final String USER_EMAIL = "candidate@gmail.com";
    private static final String CONTACT_EMAIL = "hr@company.com";

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailSenderPort emailSenderPort;

    @InjectMocks
    private EmailSendingService emailSendingService;

    @Captor
    private ArgumentCaptor<EmailDraft> draftCaptor;

    @Test
    @DisplayName("should send email and return draft with SENT status")
    void send_success_shouldReturnDraftWithSentStatus() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now());
        var job = new Job(JOB_ID, "Dev", "Company", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);
        var user = new User(USER_ID, USER_EMAIL, "Candidate", "hash");

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(emailDraftRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = emailSendingService.send(USER_ID, JOB_ID);

        assertAll(
                () -> assertEquals(EmailStatus.SENT, result.status()),
                () -> assertNotNull(result.sentAt()),
                () -> assertEquals(DRAFT_ID, result.id())
        );
        verify(emailSenderPort).send(eq(USER_EMAIL), eq(CONTACT_EMAIL), eq("Subject"), eq("Body"));
    }

    @Test
    @DisplayName("should throw EmailAlreadySentException when draft is already SENT")
    void send_whenAlreadySent_shouldThrowEmailAlreadySentException() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));

        assertThrows(EmailAlreadySentException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        verify(emailSenderPort, never()).send(any(), any(), any(), any());
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw MissingRecipientException when job has no contactEmail")
    void send_whenJobWithoutContactEmail_shouldThrowMissingRecipientException() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now());
        var job = new Job(JOB_ID, "Dev", "Company", "https://job", "Desc", LocalDate.now(), "source", null);

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThrows(MissingRecipientException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        verify(emailSenderPort, never()).send(any(), any(), any(), any());
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw EmailDeliveryException when sender fails")
    void send_whenDeliveryFails_shouldThrowEmailDeliveryException() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now());
        var job = new Job(JOB_ID, "Dev", "Company", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);
        var user = new User(USER_ID, USER_EMAIL, "Candidate", "hash");

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("SMTP error")).when(emailSenderPort).send(any(), any(), any(), any());

        assertThrows(EmailDeliveryException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should propagate EmailDeliveryException from sender without re-wrapping")
    void send_whenSenderThrowsEmailDeliveryException_shouldPropagateUnwrapped() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now());
        var job = new Job(JOB_ID, "Dev", "Company", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);
        var user = new User(USER_ID, USER_EMAIL, "Candidate", "hash");
        var deliveryException = new EmailDeliveryException("Hermes API error: 502");

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(draft));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        doThrow(deliveryException).when(emailSenderPort).send(any(), any(), any(), any());

        var thrown = assertThrows(EmailDeliveryException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        assertSame(deliveryException, thrown);
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw RefusedDraftException on REJECTED draft and never call the sender")
    void send_whenRejected_shouldThrowAndNeverCallSender() {
        var refused = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "", "NO_APPLY: non-tech role",
                EmailStatus.REJECTED, LocalDateTime.now());

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(refused));

        assertThrows(RefusedDraftException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        verify(emailSenderPort, never()).send(any(), any(), any(), any());
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("should throw EmailAlreadySentException when another draft already SENT for the same (jobId, recipient)")
    void send_whenPairAlreadySent_shouldThrowAlreadySentAndNeverCallSender() {
        var pending = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject", "Body", EmailStatus.PENDING, LocalDateTime.now());
        var job = new Job(JOB_ID, "Dev", "Company", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);
        // A different draft already delivered to the same recipient for the same vacancy
        var alreadySent = new EmailDraft(99L, JOB_ID, 7L, "Subject", "Body", EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(pending));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(emailDraftRepository.findSentByJobIdAndRecipientEmail(JOB_ID, CONTACT_EMAIL)).thenReturn(Optional.of(alreadySent));

        assertThrows(EmailAlreadySentException.class, () -> emailSendingService.send(USER_ID, JOB_ID));
        verify(emailSenderPort, never()).send(any(), any(), any(), any());
        verify(emailDraftRepository, never()).save(any());
    }
}
