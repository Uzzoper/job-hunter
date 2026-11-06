package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.IngestLinkedInPostUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.infrastructure.security.BotTokenFilter;
import com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenFilter;
import com.juanperuzzo.job_hunter.infrastructure.security.SecurityConfig;
import com.juanperuzzo.job_hunter.web.controller.JobIngestController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the ingest ROUTE against the real filter chain
 * ({@code docs/specs/linkedin-posts-ingest.md}, scenario 10
 * / 2026-11-05 round): without {@code X-Bot-Token} or a JWT the request
 * never reaches the use case. Filter-unit behavior stays with
 * {@code BotTokenFilterTest}; {@code JobIngestControllerTest} runs with
 * filters off for the HTTP-mapping matrix.
 */
@WebMvcTest(controllers = JobIngestController.class)
@Import({SecurityConfig.class, JwtTokenFilter.class, BotTokenFilter.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "bot.service.api-key=test-bot-key",
        "bot.service.owner-user-id=7"
})
@DisplayName("JobIngestController security tests (real filter chain)")
class JobIngestControllerSecurityTest {

    private static final String VALID_BODY = """
            {"url": "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678/",
             "text": "Vaga Ruby", "author": "Acme TI", "postedAt": "2026-11-04"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestLinkedInPostUseCase ingestLinkedInPostUseCase;

    /** JwtTokenFilter dependency; unused here — nothing authenticates. */
    @MockitoBean
    private TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("ingest without X-Bot-Token or JWT should return 401 and never reach the use case")
    void ingest_withoutBotTokenOrJwt_shouldReturn401AndNeverReachTheUseCase() throws Exception {
        mockMvc.perform(post("/api/jobs/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ingestLinkedInPostUseCase);
    }
}
