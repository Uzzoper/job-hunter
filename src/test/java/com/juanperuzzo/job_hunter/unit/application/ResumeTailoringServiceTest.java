package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeTailoringService;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ResumeTailoringService tests")
class ResumeTailoringServiceTest {

    @Test
    @DisplayName("tailor resume should stop before AI when the single profile is missing")
    void tailorResume_whenProfileMissing_shouldThrowWithoutCallingAi() {
        var aiPort = mock(AiPort.class);
        var jobRepository = mock(JobRepository.class);
        var analysisRepository = mock(JobAnalysisRepository.class);
        var profileRepository = mock(UserProfileRepository.class);
        var pdfRenderer = mock(PdfRendererPort.class);
        when(jobRepository.findById(3L)).thenReturn(Optional.of(new Job(3L, "Developer", "Example",
                "https://example.test/jobs/3", "Java role", LocalDate.now(), "gupy")));
        when(analysisRepository.findByJobId(3L)).thenReturn(Optional.of(new JobAnalysis(4L, 3L, 80,
                List.of("Java"), List.of(), CompanyTone.FORMAL, "Strong match")));
        when(profileRepository.find()).thenReturn(Optional.empty());
        var service = new ResumeTailoringService(aiPort, jobRepository, analysisRepository,
                profileRepository, pdfRenderer, 8000);

        assertThrows(ProfileNotConfiguredException.class, () -> service.tailorResume(3L));

        verify(aiPort, never()).complete(org.mockito.ArgumentMatchers.anyString());
    }
}
