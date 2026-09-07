package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ApproveDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.CompanyEnrichmentUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.infrastructure.security.BotTokenFilter;
import com.juanperuzzo.job_hunter.infrastructure.security.CurrentUserService;
import com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenFilter;
import com.juanperuzzo.job_hunter.infrastructure.security.SecurityConfig;
import com.juanperuzzo.job_hunter.web.controller.JobController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@Import({SecurityConfig.class, JwtTokenFilter.class, BotTokenFilter.class,
        CurrentUserService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "bot.service.api-key=",
        "bot.service.owner-user-id="
})
@DisplayName("BotTokenFilter tests (service token disabled)")
class BotTokenFilterDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FetchJobsUseCase fetchJobsUseCase;

    @MockitoBean
    private FetchSourceJobsUseCase fetchSourceJobsUseCase;

    @MockitoBean
    private AnalyzeJobUseCase analyzeJobUseCase;

    @MockitoBean
    private GenerateEmailUseCase generateEmailUseCase;

    @MockitoBean
    private SendEmailUseCase sendEmailUseCase;

    @MockitoBean
    private ApproveDraftUseCase approveDraftUseCase;

    @MockitoBean
    private ListJobsUseCase listJobsUseCase;

    @MockitoBean
    private GetJobUseCase getJobUseCase;

    @MockitoBean
    private GetEmailDraftUseCase getEmailDraftUseCase;

    @MockitoBean
    private TailorResumeUseCase tailorResumeUseCase;

    @MockitoBean
    private CompanyEnrichmentUseCase companyEnrichmentUseCase;

    @MockitoBean
    private TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("blank api-key should leave the filter inert and JWT-chain behavior unchanged (401 without JWT)")
    void noKey_whenDisabled_shouldFilterInert() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}/email", 10L)
                        .header("X-Bot-Token", "any-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(getEmailDraftUseCase);
    }
}