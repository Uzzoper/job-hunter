package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.AutoSendEligibilityService;
import com.juanperuzzo.job_hunter.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoSendEligibilityServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long JOB_ID_HIGH = 20L;
    private static final Long JOB_ID_LOW = 21L;
    private static final String CONTACT_EMAIL = "hr@company.com";

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAnalysisRepository jobAnalysisRepository;

    @Nested
    @DisplayName("full-auto mode (require-review = false)")
    class FullAutoMode {

        private AutoSendEligibilityService fullAuto;

        @BeforeEach
        void setUp() {
            fullAuto = new AutoSendEligibilityService(
                    emailDraftRepository, jobRepository, jobAnalysisRepository, false, 50);
        }

        @Test
        @DisplayName("should return highest-scoring PENDING draft")
        void nextEligibleDraft_shouldReturnHighestScore() {
            var highDraft = new EmailDraft(10L, JOB_ID_HIGH, USER_ID, "High", "Body", EmailStatus.PENDING, LocalDateTime.now(), null);
            var lowDraft = new EmailDraft(11L, JOB_ID_LOW, USER_ID, "Low", "Body", EmailStatus.PENDING, LocalDateTime.now(), null);
            var highJob = new Job(JOB_ID_HIGH, "Senior Dev", "Acme", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);
            var lowJob = new Job(JOB_ID_LOW, "Junior Dev", "Beta", "https://job2", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);

            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.PENDING))).thenReturn(List.of(highDraft, lowDraft));
            when(jobRepository.findById(JOB_ID_HIGH)).thenReturn(Optional.of(highJob));
            when(jobRepository.findById(JOB_ID_LOW)).thenReturn(Optional.of(lowJob));
            when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID_HIGH, USER_ID)).thenReturn(Optional.of(new JobAnalysis(1L, JOB_ID_HIGH, USER_ID, 85, List.of(), List.of(), CompanyTone.FORMAL, "")));
            when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID_LOW, USER_ID)).thenReturn(Optional.of(new JobAnalysis(2L, JOB_ID_LOW, USER_ID, 40, List.of(), List.of(), CompanyTone.FORMAL, "")));
            when(emailDraftRepository.countByUserIdAndStatusAndSentAtAfter(any(), eq(EmailStatus.SENT), any())).thenReturn(0L);

            var result = fullAuto.nextEligibleDraft();

            assertTrue(result.isPresent());
            assertEquals(JOB_ID_HIGH, result.get().draft().jobId());
            assertEquals(85, result.get().matchScore());
        }

        @Test
        @DisplayName("should return empty when daily cap reached")
        void nextEligibleDraft_whenDailyCapReached_shouldReturnEmpty() {
            var draft = new EmailDraft(10L, JOB_ID_HIGH, USER_ID, "Subj", "Body", EmailStatus.PENDING, LocalDateTime.now(), null);
            var job = new Job(JOB_ID_HIGH, "Dev", "Acme", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);

            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.PENDING))).thenReturn(List.of(draft));
            when(jobRepository.findById(JOB_ID_HIGH)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID_HIGH, USER_ID)).thenReturn(Optional.of(new JobAnalysis(1L, JOB_ID_HIGH, USER_ID, 70, List.of(), List.of(), CompanyTone.FORMAL, "")));
            when(emailDraftRepository.countByUserIdAndStatusAndSentAtAfter(any(), eq(EmailStatus.SENT), any())).thenReturn(50L);

            var result = fullAuto.nextEligibleDraft();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should skip job without contact email")
        void nextEligibleDraft_whenNoContactEmail_shouldSkip() {
            var draft = new EmailDraft(10L, JOB_ID_HIGH, USER_ID, "Subj", "Body", EmailStatus.PENDING, LocalDateTime.now(), null);
            var job = new Job(JOB_ID_HIGH, "Dev", "Acme", "https://job", "Desc", LocalDate.now(), "source", null);

            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.PENDING))).thenReturn(List.of(draft));
            when(jobRepository.findById(JOB_ID_HIGH)).thenReturn(Optional.of(job));

            var result = fullAuto.nextEligibleDraft();

            assertTrue(result.isEmpty());
            verifyNoInteractions(jobAnalysisRepository);
        }

        @Test
        @DisplayName("should return empty when no eligible drafts")
        void nextEligibleDraft_whenNoneEligible_shouldReturnEmpty() {
            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.PENDING))).thenReturn(List.of());

            var result = fullAuto.nextEligibleDraft();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("review-gate mode (require-review = true)")
    class ReviewGateMode {

        private AutoSendEligibilityService reviewGate;

        @BeforeEach
        void setUp() {
            reviewGate = new AutoSendEligibilityService(
                    emailDraftRepository, jobRepository, jobAnalysisRepository, true, 50);
        }

        @Test
        @DisplayName("should select APPROVED draft, not PENDING")
        void nextEligibleDraft_shouldOnlySelectApproved() {
            var approved = new EmailDraft(10L, JOB_ID_HIGH, USER_ID, "Subj", "Body", EmailStatus.APPROVED, LocalDateTime.now(), null);
            var job = new Job(JOB_ID_HIGH, "Dev", "Acme", "https://job", "Desc", LocalDate.now(), "source", CONTACT_EMAIL);

            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.APPROVED))).thenReturn(List.of(approved));
            when(jobRepository.findById(JOB_ID_HIGH)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID_HIGH, USER_ID)).thenReturn(Optional.of(new JobAnalysis(1L, JOB_ID_HIGH, USER_ID, 80, List.of(), List.of(), CompanyTone.FORMAL, "")));
            when(emailDraftRepository.countByUserIdAndStatusAndSentAtAfter(any(), eq(EmailStatus.SENT), any())).thenReturn(0L);

            var result = reviewGate.nextEligibleDraft();

            assertTrue(result.isPresent());
            assertEquals(JOB_ID_HIGH, result.get().draft().jobId());
        }

        @Test
        @DisplayName("should return empty when only PENDING drafts exist")
        void nextEligibleDraft_whenOnlyPending_shouldReturnEmpty() {
            when(emailDraftRepository.findAllByStatusIn(List.of(EmailStatus.APPROVED))).thenReturn(List.of());

            var result = reviewGate.nextEligibleDraft();

            assertTrue(result.isEmpty());
        }
    }
}
