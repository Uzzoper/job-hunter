package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.RecordExternalApplyService;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordExternalApplyService tests")
class RecordExternalApplyServiceTest {

    private static final long JOB_ID = 10L;
    private static final long USER_ID = 1L;

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @Mock
    private JobRepository jobRepository;

    private RecordExternalApplyService service;

    @BeforeEach
    void setUp() {
        service = new RecordExternalApplyService(emailDraftRepository, jobRepository);
    }

    @Test
    @DisplayName("record should persist a SENT marker with null recipient when no draft exists")
    void record_whenNoDraftExists_shouldCreateSentMarker() {
        Job job = new Job(JOB_ID, "Developer", "Acme",
                "https://acme.com/job/10", "Description", LocalDate.now(), "test");
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());
        when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(USER_ID, JOB_ID);

        assertTrue(result.created());
        assertEquals(EmailStatus.SENT, result.draft().status());
        assertNull(result.draft().recipientEmail());
        assertEquals("Subject: [Aplicação externa]", result.draft().subject());
        assertEquals("Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado.",
                result.draft().body());
        verify(emailDraftRepository).save(any());
    }

    @Test
    @DisplayName("record should return the existing SENT marker and persist nothing when already applied")
    void record_whenSentMarkerExists_shouldReturnExistingWithoutSaving() {
        Job job = new Job(JOB_ID, "Developer", "Acme",
                "https://acme.com/job/10", "Description", LocalDate.now(), "test");
        var sent = new EmailDraft(5L, JOB_ID, USER_ID,
                "Subject: [Aplicação externa]",
                "Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado.",
                EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(sent));

        var result = service.record(USER_ID, JOB_ID);

        assertEquals(sent, result.draft());
        assertFalse(result.created());
        verify(emailDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("record should supersede a pending draft in place with the SENT marker")
    void record_whenPendingDraftExists_shouldSupersedeInPlace() {
        Job job = new Job(JOB_ID, "Developer", "Acme",
                "https://acme.com/job/10", "Description", LocalDate.now(), "test");
        var pending = new EmailDraft(7L, JOB_ID, USER_ID,
                "Subject: Pending", "Body", EmailStatus.PENDING, LocalDateTime.now());
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(pending));
        when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(USER_ID, JOB_ID);

        assertFalse(result.created());
        assertEquals(pending.id(), result.draft().id());
        assertEquals(EmailStatus.SENT, result.draft().status());
        assertNull(result.draft().recipientEmail());
        assertEquals("Subject: [Aplicação externa]", result.draft().subject());
        verify(emailDraftRepository).save(any());
    }

    @Test
    @DisplayName("record should throw JobNotFoundException and persist nothing when the job does not exist")
    void record_whenJobNotFound_shouldThrowJobNotFoundException() {
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> service.record(USER_ID, JOB_ID));

        verify(emailDraftRepository, never()).save(any());
    }
}