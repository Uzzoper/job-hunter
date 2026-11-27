package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.IngestLinkedInPostUseCase;
import com.juanperuzzo.job_hunter.application.port.in.IngestResult;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.web.controller.JobIngestController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("JobIngestController tests (single-tenant)")
class JobIngestControllerTest {

    private static final String BODY = """
            {"url": "https://www.linkedin.com/feed/update/urn:li:activity:1",
             "text": "Vaga para desenvolvedor Java junior — envie CV para jobs@example.test"}
            """;

    private IngestLinkedInPostUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(IngestLinkedInPostUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new JobIngestController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("ingest should return 201 with the saved job")
    void ingest_whenSaved_shouldReturn201WithJob() throws Exception {
        var job = new Job(1L, "Junior Java Developer", "Example",
                "https://www.linkedin.com/feed/update/urn:li:activity:1", "Java role",
                LocalDate.of(2026, 11, 26), "linkedin-post", "jobs@example.test", null, false);
        when(useCase.ingest(any())).thenReturn(new IngestResult.Saved(job));

        mockMvc.perform(post("/api/jobs/ingest").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Junior Java Developer"));
    }

    @Test
    @DisplayName("ingest should return 200 with the ignored reason when the post has no contact")
    void ingest_whenNoContact_shouldReturn200WithIgnoredBody() throws Exception {
        when(useCase.ingest(any())).thenReturn(new IngestResult.NoContact());

        mockMvc.perform(post("/api/jobs/ingest").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value("no-contact"));
    }

    @Test
    @DisplayName("ingest should map an invalid body to 400 through the shared handler")
    void ingest_whenBodyInvalid_shouldReturn400() throws Exception {
        when(useCase.ingest(any())).thenThrow(new IllegalArgumentException("not a LinkedIn feed permalink"));

        mockMvc.perform(post("/api/jobs/ingest").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("ingest should map an AI gateway failure to retryable 502")
    void ingest_whenAiUnavailable_shouldReturn502() throws Exception {
        when(useCase.ingest(any())).thenThrow(new AiException("gateway down"));

        mockMvc.perform(post("/api/jobs/ingest").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Bad Gateway"));
    }
}
