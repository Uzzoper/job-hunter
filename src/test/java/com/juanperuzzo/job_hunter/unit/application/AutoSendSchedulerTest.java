package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.in.AutoSendEligibilityPort;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.infrastructure.scheduler.AutoSendScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoSendSchedulerTest {

    private static final Long USER_ID = 1L;
    private static final Long JOB_ID = 10L;

    @Mock
    private AutoSendEligibilityPort eligibilityPort;

    @Mock
    private SendEmailUseCase sendEmailUseCase;

    private AutoSendScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AutoSendScheduler(eligibilityPort, sendEmailUseCase, true, 0);
    }

    @Test
    @DisplayName("tick should call send with eligible draft userId and jobId, never regenerate")
    void tick_whenEligibleDraftExists_shouldSendWithoutRegenerating() {
        var draft = new EmailDraft(99L, JOB_ID, USER_ID, "Subj", "Body",
                EmailStatus.APPROVED, LocalDateTime.now(), null);
        var eligible = new EligibleDraft(draft, 80, "Acme", "hr@acme.com", "Dev");

        when(eligibilityPort.nextEligibleDraft()).thenReturn(Optional.of(eligible));

        scheduler.tick();

        verify(sendEmailUseCase).send(USER_ID, JOB_ID);
    }

    @Test
    @DisplayName("tick should do nothing when no eligible draft exists")
    void tick_whenNoEligibleDraft_shouldDoNothing() {
        when(eligibilityPort.nextEligibleDraft()).thenReturn(Optional.empty());

        scheduler.tick();

        verify(sendEmailUseCase, never()).send(anyLong(), anyLong());
    }

    @Test
    @DisplayName("tick should catch send exception and not propagate")
    void tick_whenSendThrows_shouldCatchAndContinue() {
        var draft = new EmailDraft(99L, JOB_ID, USER_ID, "Subj", "Body",
                EmailStatus.APPROVED, LocalDateTime.now(), null);
        var eligible = new EligibleDraft(draft, 80, "Acme", "hr@acme.com", "Dev");

        when(eligibilityPort.nextEligibleDraft()).thenReturn(Optional.of(eligible));
        doThrow(new RuntimeException("Send failed")).when(sendEmailUseCase).send(USER_ID, JOB_ID);

        scheduler.tick();

        verify(sendEmailUseCase).send(USER_ID, JOB_ID);
    }

    @Test
    @DisplayName("tick should do nothing when disabled")
    void tick_whenDisabled_shouldDoNothing() {
        scheduler = new AutoSendScheduler(eligibilityPort, sendEmailUseCase, false, 0);

        scheduler.tick();

        verifyNoInteractions(eligibilityPort, sendEmailUseCase);
    }
}
