package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.time.LocalDateTime;
import java.util.Objects;

public class EmailGenerationService implements GenerateEmailUseCase {

    private final AiPort aiPort;
    private final EmailDraftRepository emailDraftRepository;
    private final UserProfileRepository userProfileRepository;

    public EmailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository, UserProfileRepository userProfileRepository) {
        this.aiPort = aiPort;
        this.emailDraftRepository = emailDraftRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    public EmailDraft generate(Long userId, Job job, JobAnalysis analysis) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(analysis, "analysis must not be null");

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new AiException("User profile not found for userId: " + userId));

        try {
            String prompt = buildPrompt(job, analysis, profile);
            String response = aiPort.complete(prompt);
            EmailDraft draft = parseEmailDraft(job.id(), userId, response);
            return emailDraftRepository.save(draft);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to generate email: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Job job, JobAnalysis analysis, UserProfile profile) {
        String candidateProfile = """
            Candidate profile:
            - Resume: %s
            - Skills: %s
            - Preferred tone: %s
            """.formatted(profile.resumeText(), String.join(", ", profile.skills()), profile.tone().name().toLowerCase());

        String tone = analysis.companyTone().name().toLowerCase();

        String matchedSkills = String.join(", ", analysis.matchedSkills());
        String missingSkills = String.join(", ", analysis.missingSkills());

        return """
            You are an expert at writing job application emails for tech positions.

            Write an application email following the rules below:

            MANDATORY RULES:
            1. The first line must be the subject, with the exact prefix "Subject: "
            2. After one blank line, write the email body
            3. Maximum 3 paragraphs in the body
            4. Mention exactly 1 candidate project (choose the most relevant for the job)
            5. Be specific to the company and the role — generic text is not allowed
            6. Tone: %s
            7. Language: Brazilian Portuguese

            Tone guide:
            - formal: respectful language, formal verbs, "Prezados"
            - casual: natural language, straight to the point, no excess
            - startup: energy and enthusiasm, mention culture and impact

            Available projects to mention (choose the most relevant for the job):
            - Jishuu: study organization platform (Next.js, React, TypeScript, Tailwind)
            - Flappy Naruu: full stack game (React, TypeScript, Canvas API, Java, Spring, Postgres)
            - ASCII Converter: client-side image processing tool (Next.js, React, TypeScript, Canvas API)
            - Thermometer of Ponta Grossa: real-time weather site (JavaScript, Weather API)

            %s

            Job listing:
            Title: %s
            Company: %s
            Skills the candidate has for this role: %s
            Skills the candidate lacks (may mention willingness to learn): %s
            Job summary: %s
            """.formatted(tone, candidateProfile, job.title(), job.company(),
                matchedSkills, missingSkills, analysis.summary());
    }

    private EmailDraft parseEmailDraft(Long jobId, Long userId, String aiResponse) {
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

        return new EmailDraft(null, jobId, userId, subject, body, EmailStatus.PENDING, LocalDateTime.now());
    }
}
