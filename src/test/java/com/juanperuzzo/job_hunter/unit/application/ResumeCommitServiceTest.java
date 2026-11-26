package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.dto.ResumeCorrections;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeCommitService;
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
import static org.mockito.Mockito.when;

@DisplayName("ResumeCommitService tests")
class ResumeCommitServiceTest {

    @Test
    @DisplayName("commit should apply valid name and email corrections to the saved profile")
    void commit_whenIdentityCorrectionsProvided_shouldSaveCorrectedIdentity() throws Exception {
        var aiPort = mock(AiPort.class);
        var userProfileService = mock(UserProfileService.class);
        var userProfileRepository = mock(UserProfileRepository.class);
        var resumeStorage = mock(ResumeStorage.class);
        var existing = new UserProfile(5L, "Stored Owner", "old@example.com",
                "Stored resume text that is long enough to be accepted.", List.of("Java"),
                CompanyTone.CASUAL, List.of(), null, null, null, null, null, null);
        when(userProfileRepository.find()).thenReturn(Optional.of(existing));
        when(aiPort.complete(anyString())).thenReturn("""
                {"skills": ["Java"], "projects": [], "contact": null}
                """);
        when(userProfileService.saveProfile(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new ResumeCommitService(aiPort, userProfileService, userProfileRepository, resumeStorage, 8000);
        var corrections = new ResumeCorrections("Corrected Owner", "corrected@example.com",
                null, null, null, null);

        var saved = service.commit(ResumeTestFixtures.validPdf(
                        "Resume text for identity correction, with enough characters to pass validation."),
                corrections);

        assertEquals("Corrected Owner", saved.name());
        assertEquals("corrected@example.com", saved.email());
    }
}
