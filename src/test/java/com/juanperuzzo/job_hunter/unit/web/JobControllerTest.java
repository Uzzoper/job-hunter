package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.web.controller.JobController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("JobController tests")
class JobControllerTest {

    private static final long JOB_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FetchJobsUseCase fetchJobsUseCase;

    @MockitoBean
    private AnalyzeJobUseCase analyzeJobUseCase;

    @MockitoBean
    private GenerateEmailUseCase generateEmailUseCase;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private EmailDraftRepository emailDraftRepository;

    @MockitoBean
    private TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getEmailDraft should return 200 when draft belongs to authenticated user")
    void getEmailDraft_whenOwner_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var draft = new EmailDraft(
                5L, JOB_ID, 1L,
                "Subject: Application",
                "Hello, I am interested in this role.",
                EmailStatus.PENDING,
                LocalDateTime.parse("2026-05-30T10:00:00"));

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.of(draft));

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"))
                .andExpect(jsonPath("$.body").value("Hello, I am interested in this role."));

        verify(emailDraftRepository).findByJobIdAndUserId(JOB_ID, 1L);
    }

    @Test
    @DisplayName("getEmailDraft should return 404 when another user owns the draft for the same job")
    void getEmailDraft_whenOtherUser_shouldReturn404() throws Exception {
        authenticateAs(2L);

        when(emailDraftRepository.findByJobIdAndUserId(JOB_ID, 2L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isNotFound());

        verify(emailDraftRepository).findByJobIdAndUserId(JOB_ID, 2L);
        verifyNoInteractions(jobRepository);
    }

    private void authenticateAs(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(new User(userId, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
