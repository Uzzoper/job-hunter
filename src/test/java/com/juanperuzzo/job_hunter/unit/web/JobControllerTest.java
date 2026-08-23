package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ApproveDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

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

        when(getEmailDraftUseCase.getEmailDraft(1L, JOB_ID)).thenReturn(draft);

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"))
                .andExpect(jsonPath("$.body").value("Hello, I am interested in this role."));

        verify(getEmailDraftUseCase).getEmailDraft(1L, JOB_ID);
    }

    @Test
    @DisplayName("getEmailDraft should return 404 when another user owns the draft for the same job")
    void getEmailDraft_whenOtherUser_shouldReturn404() throws Exception {
        authenticateAs(2L);

        when(getEmailDraftUseCase.getEmailDraft(2L, JOB_ID)).thenThrow(new JobNotFoundException("Email draft not found for job id: " + JOB_ID));

        mockMvc.perform(get("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isNotFound());

        verify(getEmailDraftUseCase).getEmailDraft(2L, JOB_ID);
    }

    @Test
    @DisplayName("generateEmail should return 400 when job has not been analyzed for the user")
    void generateEmail_whenNoAnalysis_shouldReturn400() throws Exception {
        authenticateAs(1L);

        when(generateEmailUseCase.generate(1L, JOB_ID))
                .thenThrow(new AnalysisNotFoundException("Job must be analyzed before generating an email draft"));

        mockMvc.perform(post("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Job must be analyzed before generating an email draft"));

        verifyNoInteractions(analyzeJobUseCase);
        verify(generateEmailUseCase).generate(1L, JOB_ID);
    }

    @Test
    @DisplayName("generateEmail should return 200 when analysis exists and must not re-analyze")
    void generateEmail_whenAnalysisExists_shouldReturn200WithoutReAnalyzing() throws Exception {
        authenticateAs(1L);

        var draft = new EmailDraft(
                5L, JOB_ID, 1L,
                "Subject: Application",
                "Email body",
                EmailStatus.PENDING,
                LocalDateTime.parse("2026-05-30T10:00:00"));

        when(generateEmailUseCase.generate(eq(1L), eq(JOB_ID))).thenReturn(draft);

        mockMvc.perform(post("/api/jobs/{id}/email", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Subject: Application"));

        verifyNoInteractions(analyzeJobUseCase);
        verify(generateEmailUseCase).generate(1L, JOB_ID);
    }

    @Test
    @DisplayName("analyzeJob should return 409 when analysis already exists")
    void analyzeJob_whenDuplicate_shouldReturn409() throws Exception {
        authenticateAs(1L);

        when(analyzeJobUseCase.analyze(eq(1L), eq(10L)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

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
                new Job(1L, "Java Dev", "Acme", "https://acme.com/job1", "Description 1", LocalDate.now(), "test"),
                new Job(2L, "React Dev", "Beta", "https://beta.com/job2", "Description 2", LocalDate.now(), "test")
        );
        when(listJobsUseCase.findAll()).thenReturn(jobs);

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Java Dev"))
                .andExpect(jsonPath("$[0].company").value("Acme"))
                .andExpect(jsonPath("$[1].title").value("React Dev"))
                .andExpect(jsonPath("$[1].company").value("Beta"));

        verify(listJobsUseCase).findAll();
    }

    @Test
    @DisplayName("getAllJobs should return 200 with empty list when no jobs exist")
    void getAllJobs_whenNoJobs_shouldReturn200EmptyList() throws Exception {
        authenticateAs(1L);

        when(listJobsUseCase.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(listJobsUseCase).findAll();
    }

    @Test
    @DisplayName("getJobById should return 200 when job exists")
    void getJobById_whenJobExists_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var job = new Job(1L, "Java Dev", "Acme", "https://acme.com/job", "Description", LocalDate.now(), "test");
        when(getJobUseCase.getById(1L)).thenReturn(job);

        mockMvc.perform(get("/api/jobs/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Java Dev"))
                .andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.url").value("https://acme.com/job"))
                .andExpect(jsonPath("$.description").value("Description"));

        verify(getJobUseCase).getById(1L);
    }

    @Test
    @DisplayName("getJobById should return 404 when job does not exist")
    void getJobById_whenJobNotFound_shouldReturn404() throws Exception {
        authenticateAs(1L);

        when(getJobUseCase.getById(99L)).thenThrow(new JobNotFoundException("Job not found with id: 99"));

        mockMvc.perform(get("/api/jobs/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Job not found with id: 99"));

        verify(getJobUseCase).getById(99L);
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

    @Test
    @DisplayName("fetchLinkedInJobs should return 200 when fetch completes successfully")
    void fetchLinkedInJobs_whenValidRequest_shouldReturnSavedJobCount() throws Exception {
        authenticateAs(1L);

        mockMvc.perform(post("/api/jobs/fetch/linkedin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("LinkedIn fetch completed successfully"));

        verify(fetchSourceJobsUseCase).fetchAndSave("linkedin");
    }

    @Test
    @DisplayName("fetchLinkedInJobs should return 500 when service throws exception")
    void fetchLinkedInJobs_whenServiceFails_shouldReturnError() throws Exception {
        authenticateAs(1L);

        doThrow(new RuntimeException("LinkedIn service unavailable"))
                .when(fetchSourceJobsUseCase).fetchAndSave("linkedin");

        mockMvc.perform(post("/api/jobs/fetch/linkedin"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        verify(fetchSourceJobsUseCase).fetchAndSave("linkedin");
    }

    @Test
    @DisplayName("analyzeJob should return 200 with analysis when successful")
    void analyzeJob_whenSuccessful_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var analysis = new JobAnalysis(
                1L, 1L, 1L, 85,
                List.of("Java"), List.of("Kubernetes"),
                CompanyTone.FORMAL, "Backend role");

        when(analyzeJobUseCase.analyze(1L, 1L)).thenReturn(analysis);

        mockMvc.perform(post("/api/jobs/{id}/analyze", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.jobId").value(1))
                .andExpect(jsonPath("$.matchScore").value(85))
                .andExpect(jsonPath("$.matchedSkills[0]").value("Java"))
                .andExpect(jsonPath("$.missingSkills[0]").value("Kubernetes"))
                .andExpect(jsonPath("$.companyTone").value("FORMAL"))
                .andExpect(jsonPath("$.summary").value("Backend role"));

        verify(analyzeJobUseCase).analyze(1L, 1L);
    }

    @Test
    @DisplayName("sendEmail should return 200 with SENT draft when successful")
    void sendEmail_whenSuccessful_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var draft = new EmailDraft(
                5L, JOB_ID, 1L,
                "Subject: Application",
                "Email body",
                EmailStatus.SENT,
                LocalDateTime.parse("2026-05-30T10:00:00"),
                LocalDateTime.parse("2026-05-30T10:01:00"));

        when(sendEmailUseCase.send(1L, JOB_ID)).thenReturn(draft);

        mockMvc.perform(post("/api/jobs/{id}/send", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").value("2026-05-30T10:01:00"));

        verify(sendEmailUseCase).send(1L, JOB_ID);
    }

    @Test
    @DisplayName("approveEmail should return 200 with APPROVED status")
    void approveEmail_whenPending_shouldReturn200() throws Exception {
        authenticateAs(1L);

        var draft = new EmailDraft(
                5L, JOB_ID, 1L,
                "Subject", "Body",
                EmailStatus.APPROVED,
                LocalDateTime.parse("2026-05-30T10:00:00"));

        when(approveDraftUseCase.approve(1L, JOB_ID)).thenReturn(draft);

        mockMvc.perform(post("/api/jobs/{id}/email/approve", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        verify(approveDraftUseCase).approve(1L, JOB_ID);
    }

    @Test
    @DisplayName("sendEmail should return 409 when draft already sent")
    void sendEmail_whenAlreadySent_shouldReturn409() throws Exception {
        authenticateAs(1L);

        when(sendEmailUseCase.send(1L, JOB_ID))
                .thenThrow(new com.juanperuzzo.job_hunter.domain.exception.EmailAlreadySentException("Email 5 has already been sent"));

        mockMvc.perform(post("/api/jobs/{id}/send", JOB_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email 5 has already been sent"));
    }

    @Test
    @DisplayName("generateResume should return PDF attachment when tailored resume is generated")
    void generateResume_whenAuthenticated_shouldReturnPdfAttachment() throws Exception {
        authenticateAs(1L);

        when(tailorResumeUseCase.tailorResume(1L, JOB_ID)).thenReturn(new byte[]{37, 80, 68, 70, 1, 2, 3});

        mockMvc.perform(post("/api/jobs/{id}/resume", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("curriculo-10.pdf")));

        verify(tailorResumeUseCase).tailorResume(1L, JOB_ID);
    }

    @Test
    @DisplayName("generateResume should return 400 when job has not been analyzed for the user")
    void generateResume_whenNoAnalysis_shouldReturn400() throws Exception {
        authenticateAs(1L);

        when(tailorResumeUseCase.tailorResume(1L, JOB_ID))
                .thenThrow(new AnalysisNotFoundException("Job must be analyzed before tailoring the resume"));

        mockMvc.perform(post("/api/jobs/{id}/resume", JOB_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Job must be analyzed before tailoring the resume"));

        verify(tailorResumeUseCase).tailorResume(1L, JOB_ID);
    }

    private void authenticateAs(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(new User(userId, "test@test.com", "Test", "hash"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
