package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EmailGenerationService tests")
class EmailGenerationServiceTest {

    @Test
    @DisplayName("generate should create an AI draft from the single profile")
    void generate_whenJobHasGoodMatch_shouldSavePersonalizedDraft() {
        var aiPort = mock(AiPort.class);
        var draftRepository = mock(EmailDraftRepository.class);
        var profileRepository = mock(UserProfileRepository.class);
        var jobRepository = mock(JobRepository.class);
        var analysisRepository = mock(JobAnalysisRepository.class);
        var templateService = mock(TemplateEmailService.class);
        var memorySyncService = mock(BotMemorySyncService.class);
        var job = new Job(3L, "Junior Java Developer", "Example", "https://example.test/jobs/3",
                "Build Java services", LocalDate.now(), "gupy", "jobs@example.test");
        var analysis = new JobAnalysis(4L, 3L, 85, List.of("Java"), List.of("Kubernetes"),
                CompanyTone.FORMAL, "Strong Java match");
        var profile = new UserProfile(5L, "Owner", "owner@example.test", "Resume text for the candidate.",
                List.of("Java"), CompanyTone.FORMAL, List.of(), null, null, null, null, null, null);
        when(jobRepository.findById(3L)).thenReturn(Optional.of(job));
        when(analysisRepository.findByJobId(3L)).thenReturn(Optional.of(analysis));
        when(profileRepository.find()).thenReturn(Optional.of(profile));
        when(draftRepository.findByJobId(3L)).thenReturn(Optional.empty());
        when(draftRepository.findSentByJobIdAndRecipientEmail(3L, "jobs@example.test"))
                .thenReturn(Optional.empty());
        when(aiPort.complete(anyString())).thenReturn("Subject: Application\n\nHello, I am applying.");
        when(draftRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new EmailGenerationService(aiPort, draftRepository, profileRepository, jobRepository,
                analysisRepository, templateService, memorySyncService, 60, 8000);

        var draft = service.generate(3L);

        assertEquals("Subject: Application", draft.subject());
        assertEquals("Hello, I am applying.", draft.body());
        assertEquals("jobs@example.test", draft.recipientEmail());
    }
}
