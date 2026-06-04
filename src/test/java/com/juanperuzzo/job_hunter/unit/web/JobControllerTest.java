package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.infrastructure.security.CurrentUserService;
import com.juanperuzzo.job_hunter.web.controller.JobController;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, CurrentUserService.class})
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
    private JobAnalysisRepository jobAnalysisRepository;

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

    @Test
    @DisplayName("generateEmail should return 400 when job has not been analyzed for the user")
    void generateEmail_whenNoAnalysis_shouldReturn400() throws Exception {
        authenticateAs(1L);

        var job = new Job(JOB_ID, "Java Dev", "Acme", "https://example.com/job", "Description", LocalDate.now());
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Job must be analyzed before generating an email draft"));

        verifyNoInteractions(analyzeJobUseCase, generateEmailUseCase);
    }

    @Test
    @DisplayName("generateEmail should return 200 when analysis exists and must not re-analyze")
    void generateEmail_whenAnalysisExists_shouldReturn200WithoutReAnalyzing() throws Exception {
        authenticateAs(1L);

        var job = new Job(JOB_ID, "Java Dev", "Acme", "https://example.com/job", "Description", LocalDate.now());
        var analysis = new JobAnalysis(
                1L, JOB_ID, 1L, 85,
                List.of("Java"), List.of("Kubernetes"),
                CompanyTone.FORMAL, "Backend role");
        var draft = new EmailDraft(
                5L, JOB_ID, 1L,
                "Subject: Application",
                "Email body",
                EmailStatus.PENDING,
                LocalDateTime.parse("2026-05-30T10:00:00"));

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.of(analysis));
        when(generateEmailUseCase.generate(eq(1L), eq(job), eq(analysis))).thenReturn(draft);

        mockMvc.perform(post("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"));

        verifyNoInteractions(analyzeJobUseCase);
        verify(generateEmailUseCase).generate(1L, job, analysis);
    }

    @Test
    @DisplayName("analyzeJob should return 409 when analysis already exists")
    void analyzeJob_whenDuplicate_shouldReturn409() throws Exception {
        authenticateAs(1L);

        var job = new Job(10L, "Java Dev", "Acme", "https://example.com/job", "Description", LocalDate.now());
        when(jobRepository.findById(10L)).thenReturn(Optional.of(job));
        when(analyzeJobUseCase.analyze(eq(1L), eq(job))).thenThrow(new DataIntegrityViolationException("duplicate"));

        mockMvc.perform(post("/api/jobs/{id}/analyze", 10L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Resource already exists"));
    }

    @Test
    @DisplayName("getAllJobs should return 200 with list of jobs")
    void getAllJobs_whenJobsExist_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var jobs = List.of(
                new Job(1L, "Java Dev", "Acme", "https://acme.com/job1", "Description 1", LocalDate.now()),
                new Job(2L, "React Dev", "Beta", "https://beta.com/job2", "Description 2", LocalDate.now())
        );
        when(jobRepository.findAll()).thenReturn(jobs);

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Java Dev"))
                .andExpect(jsonPath("$[0].company").value("Acme"))
                .andExpect(jsonPath("$[1].title").value("React Dev"))
                .andExpect(jsonPath("$[1].company").value("Beta"));

        verify(jobRepository).findAll();
    }

    @Test
    @DisplayName("getAllJobs should return 200 with empty list when no jobs exist")
    void getAllJobs_whenNoJobs_shouldReturn200EmptyList() throws Exception {
        authenticateAs(1L);

        when(jobRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(jobRepository).findAll();
    }

    @Test
    @DisplayName("getJobById should return 200 when job exists")
    void getJobById_whenJobExists_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var job = new Job(1L, "Java Dev", "Acme", "https://acme.com/job", "Description", LocalDate.now());
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/jobs/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Java Dev"))
                .andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.url").value("https://acme.com/job"))
                .andExpect(jsonPath("$.description").value("Description"));

        verify(jobRepository).findById(1L);
    }

    @Test
    @DisplayName("getJobById should return 404 when job does not exist")
    void getJobById_whenJobNotFound_shouldReturn404() throws Exception {
        authenticateAs(1L);

        when(jobRepository.findById(99L)).thenThrow(new JobNotFoundException("Job not found with id: 99"));

        mockMvc.perform(get("/api/jobs/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Job not found with id: 99"));

        verify(jobRepository).findById(99L);
    }

    @Test
    @DisplayName("fetchJobs should return 200 when fetch completes successfully")
    void fetchJobs_whenSuccessful_shouldReturn200() throws Exception {
        authenticateAs(1L);

        mockMvc.perform(post("/api/jobs/fetch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Fetch completed successfully"));

        verify(fetchJobsUseCase).fetchAndSave();
    }

    private void authenticateAs(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(new User(userId, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
