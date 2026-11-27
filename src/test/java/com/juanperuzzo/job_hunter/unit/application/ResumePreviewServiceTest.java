package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumePreviewService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ResumePreviewService tests")
class ResumePreviewServiceTest {

    @Test
    @DisplayName("preview should use the single profile identity without saving changes")
    void preview_whenValidResumeUploaded_shouldReturnProfileIdentityWithoutSaving() throws Exception {
        var aiPort = mock(AiPort.class);
        var profileRepository = mock(UserProfileRepository.class);
        var stored = new UserProfile(5L, "Owner", "owner@example.test", "Stored resume text long enough to pass.",
                List.of("Java"), CompanyTone.CASUAL, List.of(), null, null, null, null, null, null);
        when(profileRepository.find()).thenReturn(Optional.of(stored));
        when(aiPort.complete(anyString())).thenReturn("""
                {"skills": ["Java"], "projects": [],
                 "contact": {"phone": "+55 11 90000-0000", "email": "contact@example.test",
                             "portfolioUrl": "https://example.test", "githubUrl": null, "linkedinUrl": null}}
                """);
        var service = new ResumePreviewService(aiPort, profileRepository, 8000);

        var preview = service.preview(ResumeTestFixtures.validPdf(
                "A resume with enough text to pass validation and preview extraction."));

        assertEquals("Owner", preview.accountIdentity().name());
        assertEquals("owner@example.test", preview.accountIdentity().email());
        assertEquals("+55 11 90000-0000", preview.contact().phone());
        verify(profileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
