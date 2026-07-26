package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

public class EmailGenerationService implements GenerateEmailUseCase, GetEmailDraftUseCase {

    private static final int MAX_RESUME_CHARS = 1000;

    private final AiPort aiPort;
    private final EmailDraftRepository emailDraftRepository;
    private final UserProfileRepository userProfileRepository;
    private final JobRepository jobRepository;
    private final JobAnalysisRepository jobAnalysisRepository;

    public EmailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository,
                                  UserProfileRepository userProfileRepository,
                                  JobRepository jobRepository, JobAnalysisRepository jobAnalysisRepository) {
        this.aiPort = aiPort;
        this.emailDraftRepository = emailDraftRepository;
        this.userProfileRepository = userProfileRepository;
        this.jobRepository = jobRepository;
        this.jobAnalysisRepository = jobAnalysisRepository;
    }

    @Override
    public EmailDraft generate(Long userId, Long jobId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + jobId));
        JobAnalysis analysis = jobAnalysisRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new AnalysisNotFoundException(
                        "Job must be analyzed before generating an email draft"));

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new AiException("User profile not found for userId: " + userId));

        try {
            String prompt = buildPrompt(job, analysis, profile);
            String response = aiPort.complete(prompt);
            var existingId = emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
                    .map(EmailDraft::id)
                    .orElse(null);
            EmailDraft draft = parseEmailDraft(existingId, job.id(), userId, response);
            return emailDraftRepository.save(draft);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to generate email: " + e.getMessage(), e);
        }
    }

    @Override
    public EmailDraft getEmailDraft(Long userId, Long jobId) {
        return emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new JobNotFoundException("Email draft not found for job id: " + jobId));
    }

    private String buildPrompt(Job job, JobAnalysis analysis, UserProfile profile) {
        String resumeExcerpt = profile.resumeText().length() <= MAX_RESUME_CHARS
                ? profile.resumeText()
                : profile.resumeText().substring(0, MAX_RESUME_CHARS) + "...";

        String tone = analysis.companyTone().name().toLowerCase();
        String matchedSkills = String.join(", ", analysis.matchedSkills());
        String missingSkills = String.join(", ", analysis.missingSkills());

        String projectsText = profile.projects().isEmpty()
                ? "No projects available."
                : profile.projects().stream()
                        .map(p -> "- " + p.name() + ": " + p.description() + " (" + p.techStack() + ")")
                        .collect(Collectors.joining("\n"));

        return """
            You are an expert at writing job application emails for tech positions.

            Write an email following the rules below:
            1. First line must be "Subject: " followed by the subject
            2. After a blank line, write the email body (max 3 paragraphs)
            3. Mention exactly 1 candidate project (choose the most relevant)
            4. Be specific to the company and role
            5. Tone: %s
            6. Language: Brazilian Portuguese

            Tone guide:
            - formal: respectful, "Prezados"
            - casual: natural, direct
            - startup: energetic, mention culture and impact

            Candidate profile:
            - Resume: %s
            - Skills: %s

            Available projects:
            %s

            Job: %s at %s
            Matched skills: %s
            Missing skills (show willingness to learn): %s
            Summary: %s
            """.formatted(tone, resumeExcerpt, String.join(", ", profile.skills()),
                projectsText, job.title(), job.company(),
                matchedSkills, missingSkills, analysis.summary());
    }

    private EmailDraft parseEmailDraft(Long id, Long jobId, Long userId, String aiResponse) {
        String subject;
        String body;

        int subjectEnd = aiResponse.indexOf('\n');
        if (subjectEnd > 0) {
            subject = aiResponse.substring(0, subjectEnd).trim();
            body = aiResponse.substring(subjectEnd).trim();
        } else {
            subject = aiResponse.trim();
            body = "";
        }

        if (!subject.startsWith("Subject: ")) {
            subject = "Subject: " + subject;
        }

        return new EmailDraft(id, jobId, userId, subject, body, EmailStatus.PENDING, LocalDateTime.now());
    }
}
