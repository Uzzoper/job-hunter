package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.IngestLinkedInPostUseCase;
import com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand;
import com.juanperuzzo.job_hunter.application.port.in.IngestResult;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.web.controller.JobIngestController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RED tests for {@code docs/specs/linkedin-posts-ingest.md} scenarios 1, 6
 * and 8 (HTTP mapping). Application scenarios live in
 * {@code IngestLinkedInPostServiceTest}; the 401 authentication contract is
 * pinned by {@code BotTokenFilterTest} and the filter chain runs ahead of
 * every route by construction.
 */
@WebMvcTest(controllers = JobIngestController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class})
@DisplayName("JobIngestController tests")
class JobIngestControllerTest {

    private static final String PERMALINK =
            "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestLinkedInPostUseCase ingestLinkedInPostUseCase;

    /** @WebMvcTest scans Filter beans; JwtTokenFilter needs the port (mirrors JobControllerTest). */
    @MockitoBean
    private com.juanperuzzo.job_hunter.application.port.out.TokenProvider tokenProvider;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String validBody() {
        return """
                {"url": "%s", "text": "Estamos contratando desenvolvedor!",
                 "author": "Acme TI", "postedAt": "2026-11-04"}
                """.formatted(PERMALINK);
    }

    private static final String BAD_PERMALINK = "https://www.linkedin.com/posts/algo-aleatorio";

    /**
     * Body validation lives in the use case (pipeline step 1); this pins the
     * HTTP mapping of its {@link IllegalArgumentException} (400) for the
     * invalid-permalink/blank-text paths.
     */
    @Test
    @DisplayName("ingest should return 400 when the use case rejects the body")
    void ingest_whenBodyValidationFails_shouldReturn400() throws Exception {
        when(ingestLinkedInPostUseCase.ingest(any(IngestPostCommand.class)))
                .thenThrow(new IllegalArgumentException(
                        "url must be a linkedin.com/feed/update/urn:li:activity:<id> permalink"));

        mockMvc.perform(post("/api/jobs/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace(PERMALINK, BAD_PERMALINK)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ingest should return 201 with the saved job back")
    void ingest_whenSaved_shouldReturn201WithJob() throws Exception {
        authenticateAs(1L);
        var savedJob = new Job(1L, "Dev Ruby", "Acme TI", PERMALINK, "desc",
                LocalDate.of(2026, 11, 4), "linkedin-post", "rh@acme.com.br");
        when(ingestLinkedInPostUseCase.ingest(any(IngestPostCommand.class)))
                .thenReturn(new IngestResult.Saved(savedJob));

        mockMvc.perform(post("/api/jobs/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Dev Ruby"))
                .andExpect(jsonPath("$.url").value(PERMALINK))
                .andExpect(jsonPath("$.contactEmail").value("rh@acme.com.br"));
    }

    @Test
    @DisplayName("ingest should return 200 with the ignored reason when the use case filters it out")
    void ingest_whenNoContact_shouldReturn200WithIgnoredBody() throws Exception {
        authenticateAs(1L);
        when(ingestLinkedInPostUseCase.ingest(any(IngestPostCommand.class)))
                .thenReturn(new IngestResult.NoContact());

        mockMvc.perform(post("/api/jobs/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value("no-contact"));
    }

    @Test
    @DisplayName("ingest should return 502 when the AI gateway fails (existing AiException mapping)")
    void ingest_whenAiUnavailable_shouldReturn502() throws Exception {
        authenticateAs(1L);
        doThrow(new AiException("hermes gateway down"))
                .when(ingestLinkedInPostUseCase).ingest(any(IngestPostCommand.class));

        mockMvc.perform(post("/api/jobs/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("hermes gateway down"));
    }

    private void authenticateAs(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(
                new User(userId, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
