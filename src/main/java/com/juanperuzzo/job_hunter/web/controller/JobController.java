package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.application.port.in.CurrentUserProvider;
import com.juanperuzzo.job_hunter.web.dto.EmailDraftResponse;
import com.juanperuzzo.job_hunter.web.dto.JobResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final FetchJobsUseCase fetchJobsUseCase;
    private final FetchSourceJobsUseCase fetchSourceJobsUseCase;
    private final AnalyzeJobUseCase analyzeJobUseCase;
    private final GenerateEmailUseCase generateEmailUseCase;
    private final ListJobsUseCase listJobsUseCase;
    private final GetJobUseCase getJobUseCase;
    private final GetEmailDraftUseCase getEmailDraftUseCase;
    private final SendEmailUseCase sendEmailUseCase;
    private final CurrentUserProvider currentUserService;

    public JobController(
            FetchJobsUseCase fetchJobsUseCase,
            FetchSourceJobsUseCase fetchSourceJobsUseCase,
            AnalyzeJobUseCase analyzeJobUseCase,
            GenerateEmailUseCase generateEmailUseCase,
            ListJobsUseCase listJobsUseCase,
            GetJobUseCase getJobUseCase,
            GetEmailDraftUseCase getEmailDraftUseCase,
            SendEmailUseCase sendEmailUseCase,
            CurrentUserProvider currentUserService) {
        this.fetchJobsUseCase = fetchJobsUseCase;
        this.fetchSourceJobsUseCase = fetchSourceJobsUseCase;
        this.analyzeJobUseCase = analyzeJobUseCase;
        this.generateEmailUseCase = generateEmailUseCase;
        this.listJobsUseCase = listJobsUseCase;
        this.getJobUseCase = getJobUseCase;
        this.getEmailDraftUseCase = getEmailDraftUseCase;
        this.sendEmailUseCase = sendEmailUseCase;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> getAllJobs() {
        List<Job> jobs = listJobsUseCase.findAll();
        List<JobResponse> response = jobs.stream()
                .map(job -> new JobResponse(
                        job.id(),
                        job.title(),
                        job.company(),
                        job.url(),
                        job.description(),
                        job.postedAt(),
                        job.source()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable Long id) {
        Job job = getJobUseCase.getById(id);
        JobResponse response = new JobResponse(
                job.id(),
                job.title(),
                job.company(),
                job.url(),
                job.description(),
                job.postedAt(),
                job.source()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<JobAnalysis> analyzeJob(@PathVariable Long id) {
        Long userId = currentUserService.getCurrentUserId();
        JobAnalysis analysis = analyzeJobUseCase.analyze(userId, id);
        return ResponseEntity.ok(analysis);
    }

    @PostMapping("/{id}/email")
    public ResponseEntity<EmailDraftResponse> generateEmail(@PathVariable Long id) {
        Long userId = currentUserService.getCurrentUserId();
        EmailDraft emailDraft = generateEmailUseCase.generate(userId, id);
        EmailDraftResponse response = new EmailDraftResponse(
                emailDraft.id(),
                emailDraft.jobId(),
                emailDraft.subject(),
                emailDraft.body(),
                emailDraft.status(),
                emailDraft.generatedAt()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fetch")
    public ResponseEntity<?> fetchJobs() {
        fetchJobsUseCase.fetchAndSave();
        return ResponseEntity.ok(java.util.Map.of("message", "Fetch completed successfully"));
    }

    @PostMapping("/fetch/linkedin")
    public ResponseEntity<?> fetchLinkedInJobs() {
        fetchSourceJobsUseCase.fetchAndSave("linkedin");
        return ResponseEntity.ok(java.util.Map.of("message", "LinkedIn fetch completed successfully"));
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<EmailDraftResponse> sendEmail(@PathVariable Long id) {
        Long userId = currentUserService.getCurrentUserId();
        EmailDraft emailDraft = sendEmailUseCase.send(userId, id);
        EmailDraftResponse response = new EmailDraftResponse(
                emailDraft.id(),
                emailDraft.jobId(),
                emailDraft.subject(),
                emailDraft.body(),
                emailDraft.status(),
                emailDraft.generatedAt(),
                emailDraft.sentAt()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/email")
    public ResponseEntity<EmailDraftResponse> getEmailDraft(@PathVariable Long id) {
        Long userId = currentUserService.getCurrentUserId();
        EmailDraft emailDraft = getEmailDraftUseCase.getEmailDraft(userId, id);
        EmailDraftResponse response = new EmailDraftResponse(
                emailDraft.id(),
                emailDraft.jobId(),
                emailDraft.subject(),
                emailDraft.body(),
                emailDraft.status(),
                emailDraft.generatedAt()
        );
        return ResponseEntity.ok(response);
    }

}
