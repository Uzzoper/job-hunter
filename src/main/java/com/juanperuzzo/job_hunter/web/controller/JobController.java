package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ApproveDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillJobDescriptionsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.CompanyEnrichmentUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchLinkedInAsyncUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchRunStatusUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.JobWithDraftStatus;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.web.dto.EmailDraftResponse;
import com.juanperuzzo.job_hunter.web.dto.EnrichmentResultResponse;
import com.juanperuzzo.job_hunter.web.dto.ExternalApplyResponse;
import com.juanperuzzo.job_hunter.web.dto.FetchResultResponse;
import com.juanperuzzo.job_hunter.web.dto.FetchRunStatusResponse;
import com.juanperuzzo.job_hunter.web.dto.FetchStartedResponse;
import com.juanperuzzo.job_hunter.web.dto.BackfillResultResponse;
import com.juanperuzzo.job_hunter.web.dto.BackfillDescriptionsResponse;
import com.juanperuzzo.job_hunter.web.dto.JobResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final FetchJobsUseCase fetchJobsUseCase;
    private final FetchSourceJobsUseCase fetchSourceJobsUseCase;
    private final FetchLinkedInAsyncUseCase fetchLinkedInAsyncUseCase;
    private final FetchRunStatusUseCase fetchRunStatusUseCase;
    private final AnalyzeJobUseCase analyzeJobUseCase;
    private final GenerateEmailUseCase generateEmailUseCase;
    private final ListJobsUseCase listJobsUseCase;
    private final GetJobUseCase getJobUseCase;
    private final GetEmailDraftUseCase getEmailDraftUseCase;
    private final ApproveDraftUseCase approveDraftUseCase;
    private final SendEmailUseCase sendEmailUseCase;
    private final TailorResumeUseCase tailorResumeUseCase;
    private final CompanyEnrichmentUseCase companyEnrichmentUseCase;
    private final RecordExternalApplyUseCase recordExternalApplyUseCase;
    private final BackfillContactEmailsUseCase backfillContactEmailsUseCase;
    private final BackfillCompanyWebsitesUseCase backfillCompanyWebsitesUseCase;
    private final BackfillJobDescriptionsUseCase backfillJobDescriptionsUseCase;

    public JobController(
            FetchJobsUseCase fetchJobsUseCase,
            FetchSourceJobsUseCase fetchSourceJobsUseCase,
            FetchLinkedInAsyncUseCase fetchLinkedInAsyncUseCase,
            FetchRunStatusUseCase fetchRunStatusUseCase,
            AnalyzeJobUseCase analyzeJobUseCase,
            GenerateEmailUseCase generateEmailUseCase,
            ListJobsUseCase listJobsUseCase,
            GetJobUseCase getJobUseCase,
            GetEmailDraftUseCase getEmailDraftUseCase,
            ApproveDraftUseCase approveDraftUseCase,
            SendEmailUseCase sendEmailUseCase,
            TailorResumeUseCase tailorResumeUseCase,
            CompanyEnrichmentUseCase companyEnrichmentUseCase,
            RecordExternalApplyUseCase recordExternalApplyUseCase,
            BackfillContactEmailsUseCase backfillContactEmailsUseCase,
            BackfillCompanyWebsitesUseCase backfillCompanyWebsitesUseCase,
            BackfillJobDescriptionsUseCase backfillJobDescriptionsUseCase) {
        this.fetchJobsUseCase = fetchJobsUseCase;
        this.fetchSourceJobsUseCase = fetchSourceJobsUseCase;
        this.fetchLinkedInAsyncUseCase = fetchLinkedInAsyncUseCase;
        this.fetchRunStatusUseCase = fetchRunStatusUseCase;
        this.analyzeJobUseCase = analyzeJobUseCase;
        this.generateEmailUseCase = generateEmailUseCase;
        this.listJobsUseCase = listJobsUseCase;
        this.getJobUseCase = getJobUseCase;
        this.getEmailDraftUseCase = getEmailDraftUseCase;
        this.approveDraftUseCase = approveDraftUseCase;
        this.sendEmailUseCase = sendEmailUseCase;
        this.tailorResumeUseCase = tailorResumeUseCase;
        this.companyEnrichmentUseCase = companyEnrichmentUseCase;
        this.recordExternalApplyUseCase = recordExternalApplyUseCase;
        this.backfillContactEmailsUseCase = backfillContactEmailsUseCase;
        this.backfillCompanyWebsitesUseCase = backfillCompanyWebsitesUseCase;
        this.backfillJobDescriptionsUseCase = backfillJobDescriptionsUseCase;
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> getAllJobs(
            @RequestParam(required = false) Boolean hasEmail,
            @RequestParam(required = false) Boolean excludeApplied,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Boolean remoteOnly) {
        List<JobResponse> response = listJobsUseCase.findAllWithDraftStatus(hasEmail, excludeApplied, minScore, remoteOnly).stream()
                .map(this::toJobResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable Long id) {
        JobWithDraftStatus entry = getJobUseCase.getByIdWithDraftStatus(id);
        return ResponseEntity.ok(toJobResponse(entry));
    }

    private JobResponse toJobResponse(JobWithDraftStatus entry) {
        Job job = entry.job();
        return JobResponse.from(job, entry.draftStatus(), entry.matchScore(), entry.lifecycleState());
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<JobAnalysis> analyzeJob(@PathVariable Long id) {
        JobAnalysis analysis = analyzeJobUseCase.analyze(id);
        return ResponseEntity.ok(analysis);
    }

    @PostMapping("/{id}/email")
    public ResponseEntity<EmailDraftResponse> generateEmail(@PathVariable Long id) {
        EmailDraft emailDraft = generateEmailUseCase.generate(id);
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

    @PostMapping("/{id}/applied")
    public ResponseEntity<ExternalApplyResponse> recordExternalApply(@PathVariable Long id) {
        var result = recordExternalApplyUseCase.record(id);
        var body = new ExternalApplyResponse(result.draft().jobId(), result.draft().status());
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/fetch")
    public ResponseEntity<FetchResultResponse> fetchJobs() {
        var result = fetchJobsUseCase.fetchAndSave();
        return ResponseEntity.ok(FetchResultResponse.from(result));
    }

    /**
     * Async LinkedIn fetch (issue #116, docs/specs/linkedin-async-fetch.md):
     * answers before the fetch finishes — the caller polls the status endpoint
     * instead of blocking. Single-flight: 409 while a run is active. The userId
     * is resolved here so runtime keywords are the caller's own.
     */
    @PostMapping("/fetch/linkedin")
    public ResponseEntity<FetchStartedResponse> fetchLinkedInJobs() {
        var started = fetchLinkedInAsyncUseCase.start();
        return started.outcome() == FetchLinkedInAsyncUseCase.StartOutcome.STARTED
                ? ResponseEntity.accepted().body(FetchStartedResponse.started(started.startedAt()))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(FetchStartedResponse.running(started.startedAt()));
    }

    /**
     * Poll side of the async LinkedIn fetch (issue #116): idle | running |
     * completed | failed, counts and per-keyword outcomes — no keyword is
     * skipped without a caller-visible entry.
     */
    @GetMapping("/fetch/linkedin/status")
    public ResponseEntity<FetchRunStatusResponse> fetchLinkedInStatus() {
        return ResponseEntity.ok(FetchRunStatusResponse.from(fetchRunStatusUseCase.status()));
    }

    @PostMapping("/fetch/gupy")
    public ResponseEntity<FetchResultResponse> fetchGupyJobs() {
        var result = fetchSourceJobsUseCase.fetchAndSave("gupy");
        return ResponseEntity.ok(FetchResultResponse.from(result));
    }

    @PostMapping("/fetch/infojobs")
    public ResponseEntity<FetchResultResponse> fetchInfoJobs() {
        var result = fetchSourceJobsUseCase.fetchAndSave("infojobs");
        return ResponseEntity.ok(FetchResultResponse.from(result));
    }

    @PostMapping("/fetch/lever")
    public ResponseEntity<FetchResultResponse> fetchLeverJobs() {
        var result = fetchSourceJobsUseCase.fetchAndSave("lever");
        return ResponseEntity.ok(FetchResultResponse.from(result));
    }

    @PostMapping("/enrich-emails")
    public ResponseEntity<EnrichmentResultResponse> enrichEmails(
            @RequestParam(defaultValue = "${scraper.enricher.batch-default-limit:200}") int limit) {
        var result = companyEnrichmentUseCase.enrichMissingEmails(limit);
        return ResponseEntity.ok(EnrichmentResultResponse.from(result));
    }

    @PostMapping("/backfill-emails")
    public ResponseEntity<BackfillResultResponse> backfillEmails(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        var result = backfillContactEmailsUseCase.run(dryRun);
        return ResponseEntity.ok(BackfillResultResponse.from(result));
    }

    /**
     * Backfill company websites on stored Gupy rows (gupy-detail-domains spec §7).
     *
     * <p>{@code afterId} pages the scan by row id (exclusive lower bound, rows ordered
     * by id): the resolver caps at the first {@code maxHosts} distinct hosts, so a dead
     * head would otherwise be retried on every round and starve everything behind it.
     * Omit it to scan from the start; the bot pages explicit id ranges and finishes with
     * a final sweep without {@code afterId}.
     */
    @PostMapping("/backfill-websites")
    public ResponseEntity<BackfillResultResponse> backfillWebsites(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "50") int maxHosts,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) String source) {
        var result = backfillCompanyWebsitesUseCase.run(dryRun, maxHosts, afterId, source);
        return ResponseEntity.ok(BackfillResultResponse.from(result));
    }

    /**
     * Backfill descriptions on stored InfoJobs rows
     * ({@code docs/specs/infojobs-description-backfill.md}).
     * Rows whose description is already at least {@code maxDescriptionChars}
     * characters are never candidates, so nothing can be overwritten.
     */
    @PostMapping("/backfill-descriptions")
    public ResponseEntity<BackfillDescriptionsResponse> backfillDescriptions(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "300") int maxDescriptionChars,
            @RequestParam(required = false) Long afterId) {
        return ResponseEntity.ok(BackfillDescriptionsResponse.from(
                backfillJobDescriptionsUseCase.run(dryRun, limit, maxDescriptionChars, afterId)));
    }

    @PostMapping("/{id}/email/approve")
    public ResponseEntity<EmailDraftResponse> approveEmail(@PathVariable Long id) {
        var draft = approveDraftUseCase.approve(id);
        var response = new EmailDraftResponse(
                draft.id(), draft.jobId(), draft.subject(), draft.body(),
                draft.status(), draft.generatedAt(), draft.sentAt());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<EmailDraftResponse> sendEmail(@PathVariable Long id) {
        EmailDraft emailDraft = sendEmailUseCase.send(id);
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
        EmailDraft emailDraft = getEmailDraftUseCase.getEmailDraft(id);
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

    @PostMapping("/{id}/resume")
    public ResponseEntity<byte[]> generateResume(@PathVariable Long id) {
        byte[] pdf = tailorResumeUseCase.tailorResume(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"curriculo-" + id + ".pdf\"")
                .body(pdf);
    }

}
