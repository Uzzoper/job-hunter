package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.EmailSendingService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmailSendingService tests")
class EmailSendingServiceTest {

    @Test
    @DisplayName("send should deliver using the single profile identity and mark the draft sent")
    void send_whenDraftIsApproved_shouldSendAndPersistSentStatus() {
        var draftRepository = mock(EmailDraftRepository.class);
        var jobRepository = mock(JobRepository.class);
        var profileRepository = mock(UserProfileRepository.class);
        var sender = mock(EmailSenderPort.class);
        var generatedAt = LocalDateTime.of(2026, 11, 26, 10, 0);
        var draft = new EmailDraft(7L, 3L, "Subject: Application", "Hello, I am applying.",
                EmailStatus.APPROVED, generatedAt, null, "jobs@example.test");
        var job = new Job(3L, "Developer", "Example", "https://example.test/jobs/3", "Java role",
                LocalDate.now(), "gupy", "jobs@example.test");
        var profile = new UserProfile(5L, "Owner", "owner@example.test", "Resume text.",
                List.of("Java"), CompanyTone.FORMAL, List.of(), null, null, null, null, null, null);
        when(draftRepository.findByJobId(3L)).thenReturn(Optional.of(draft));
        when(jobRepository.findById(3L)).thenReturn(Optional.of(job));
        when(draftRepository.findSentByJobIdAndRecipientEmail(3L, "jobs@example.test"))
                .thenReturn(Optional.empty());
        when(profileRepository.find()).thenReturn(Optional.of(profile));
        when(draftRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var sent = new EmailSendingService(draftRepository, jobRepository, profileRepository, sender).send(3L);

        assertEquals(EmailStatus.SENT, sent.status());
        assertEquals("jobs@example.test", sent.recipientEmail());
        verify(sender).send("owner@example.test", "jobs@example.test", "Subject: Application", "Hello, I am applying.");
    }
}
