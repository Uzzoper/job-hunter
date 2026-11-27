package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ResumeUploadService tests")
class ResumeUploadServiceTest {

    @Test
    @DisplayName("upload should preserve the single profile identity and store the PDF")
    void uploadResume_whenValidResumeUploaded_shouldPreserveIdentityAndStorePdf() throws Exception {
        var aiPort = mock(AiPort.class);
        var userProfileService = mock(UserProfileService.class);
        var profileRepository = mock(UserProfileRepository.class);
        var resumeStorage = mock(ResumeStorage.class);
        var stored = new UserProfile(5L, "Owner", "owner@example.test", "Previous resume text long enough.",
                List.of("Java"), CompanyTone.CASUAL, List.of(), null, null, null, null, null, null);
        when(profileRepository.find()).thenReturn(Optional.of(stored));
        when(aiPort.complete(anyString())).thenReturn("""
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "+55 11 90000-0000", "email": "contact@example.test",
                             "portfolioUrl": null, "githubUrl": null, "linkedinUrl": null}}
                """);
        when(userProfileService.saveProfile(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ResumeUploadService(aiPort, userProfileService, profileRepository, resumeStorage, 8000);

        var saved = service.uploadResume(ResumeTestFixtures.validPdf(
                "A replacement resume with enough text to pass validation and upload."));

        assertEquals("Owner", saved.name());
        assertEquals("owner@example.test", saved.email());
        assertEquals(CompanyTone.CASUAL, saved.tone());
        verify(resumeStorage).write(any());
    }
}
