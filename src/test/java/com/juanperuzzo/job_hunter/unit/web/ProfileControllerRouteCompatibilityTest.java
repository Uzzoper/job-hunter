package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.dto.ResumePreview;
import com.juanperuzzo.job_hunter.application.port.in.AccountIdentity;
import com.juanperuzzo.job_hunter.application.port.in.ResumeRetrievalUseCase;
import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.service.ResumeCommitService;
import com.juanperuzzo.job_hunter.application.service.ResumePreviewService;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.web.controller.ProfileController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ProfileController route compatibility tests")
class ProfileControllerRouteCompatibilityTest {

    private ResumePreviewService resumePreviewService;
    private ResumeCommitService resumeCommitService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var userProfileService = mock(UserProfileUseCase.class);
        var resumeUploadService = mock(ResumeUploadService.class);
        var resumeRetrievalUseCase = mock(ResumeRetrievalUseCase.class);
        resumePreviewService = mock(ResumePreviewService.class);
        resumeCommitService = mock(ResumeCommitService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProfileController(
                userProfileService, resumeUploadService, resumeRetrievalUseCase,
                resumePreviewService, resumeCommitService)).build();
    }

    @Test
    @DisplayName("preview resume should remain available at the documented route")
    void previewResume_whenUsingDocumentedRoute_shouldReturnOk() throws Exception {
        when(resumePreviewService.preview(any())).thenReturn(new ResumePreview(
                new AccountIdentity("Owner", null),
                new ResumePreview.ContactPreview(null, null, null),
                List.of(), List.of(), CompanyTone.FORMAL, 100, "digest", List.of()));

        mockMvc.perform(multipart("/api/profile/resume/preview")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("commit resume should remain available at the documented route")
    void commitResume_whenUsingDocumentedRoute_shouldReturnOk() throws Exception {
        when(resumeCommitService.commit(any(), isNull())).thenReturn(emptyProfile());

        mockMvc.perform(multipart("/api/profile/resume/commit")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isOk());
    }

    private static UserProfile emptyProfile() {
        return new UserProfile(1L, "Owner", null, "A resume with enough text to satisfy the profile contract.",
                List.of("Java"), CompanyTone.FORMAL, List.of(), null, null, null, null, null, null);
    }
}
