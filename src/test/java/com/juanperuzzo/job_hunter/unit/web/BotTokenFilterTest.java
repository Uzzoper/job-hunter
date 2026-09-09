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
import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.User;
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

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@Import({SecurityConfig.class, JwtTokenFilter.class, BotTokenFilter.class,
        CurrentUserService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "bot.service.api-key=test-bot-key",
        "bot.service.owner-user-id=7"
})
@DisplayName("BotTokenFilter tests (service token enabled)")
class BotTokenFilterTest {

    private static final long JOB_ID = 10L;

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
    private RecordExternalApplyUseCase recordExternalApplyUseCase;

    @MockitoBean
    private TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("valid X-Bot-Token should authenticate as the configured owner user and return 200")
    void validToken_whenConfigured_shouldAuthenticateAsOwner() throws Exception {
        var draft = new EmailDraft(
                5L, JOB_ID, 7L,
                "Subject: Application",
                "Email body",
                EmailStatus.PENDING,
                LocalDateTime.parse("2026-05-30T10:00:00"));
        when(getEmailDraftUseCase.getEmailDraft(7L, JOB_ID)).thenReturn(draft);

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID)
                        .header("X-Bot-Token", "test-bot-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"));

        verify(getEmailDraftUseCase).getEmailDraft(7L, JOB_ID);
    }

    @Test
    @DisplayName("valid bot token plus a Bearer JWT should keep the bot principal and scope the request to the owner")
    void validBotTokenAndJwt_shouldPreserveBotPrincipal() throws Exception {
        var draft = new EmailDraft(
                5L, JOB_ID, 7L,
                "Subject: Application",
                "Email body",
                EmailStatus.PENDING,
                LocalDateTime.parse("2026-05-30T10:00:00"));
        when(getEmailDraftUseCase.getEmailDraft(7L, JOB_ID)).thenReturn(draft);
        // The JWT resolves to a different user (99); if it overwrote the bot
        // principal, the request would be scoped to 99 instead of owner 7.
        when(tokenProvider.validate("other-user-jwt"))
                .thenReturn(new User(99L, "other@example.com", "other", "hash"));

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID)
                        .header("X-Bot-Token", "test-bot-key")
                        .header("Authorization", "Bearer other-user-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"));

        // Both tokens were presented; the bot principal must win: the request stays
        // scoped to the owner and the JWT filter never even consults the token provider
        // (guard on an already-present authentication skips the JWT entirely).
        verify(getEmailDraftUseCase).getEmailDraft(7L, JOB_ID);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    @DisplayName("wrong X-Bot-Token should fall through to the JWT chain and return 401 without JWT")
    void invalidToken_whenConfigured_shouldFallThroughToJwt() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID)
                        .header("X-Bot-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(getEmailDraftUseCase);
    }

    @Test
    @DisplayName("missing X-Bot-Token should leave JWT-chain behavior unchanged and return 401 without JWT")
    void missingToken_whenConfigured_shouldFallThroughToJwt() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(getEmailDraftUseCase);
    }
}