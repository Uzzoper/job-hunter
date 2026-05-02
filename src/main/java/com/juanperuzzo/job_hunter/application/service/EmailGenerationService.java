package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;

import java.time.LocalDateTime;
import java.util.Objects;

public class EmailGenerationService implements GenerateEmailUseCase {

    private final AiPort aiPort;
    private final EmailDraftRepository emailDraftRepository;

    public EmailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository) {
        this.aiPort = aiPort;
        this.emailDraftRepository = emailDraftRepository;
    }

    @Override
    public EmailDraft generate(Job job, JobAnalysis analysis) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(analysis, "analysis must not be null");

        try {
            String prompt = buildPrompt(job, analysis);
            String response = aiPort.complete(prompt);
            EmailDraft draft = parseEmailDraft(job.id(), response);
            return emailDraftRepository.save(draft);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to generate email: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Job job, JobAnalysis analysis) {
        String candidateProfile = """
            Candidate profile:
            - Name: Juan Antonio Peruzzo
            - Education: Software Engineering (Unicesumar, in progress)
            - Stack: Java, Spring Boot, TypeScript, React, Next.js, Postgres, SQL, HTML, CSS, JavaScript, Git, GitHub, Docker
            - Portfolio: https://juanperuzzo.is-a.dev
            - GitHub: https://github.com/Uzzoper
            - Projects:
              * Jishuu — study organization platform (Next.js, React, TypeScript, Tailwind)
              * Flappy Naruu — full stack game (React, TypeScript, Canvas API, Java, Spring, Postgres)
              * ASCII Converter — client-side image processing tool (Next.js, React, TypeScript, Canvas API)
              * Thermometer of Ponta Grossa — real-time weather site (JavaScript, Weather API)
            - English: advanced (fluent reading, intermediate conversation)
            - Location: Ponta Grossa – PR, Brazil (open to remote)
            - Goal: internship or junior developer position
            """;

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

    private EmailDraft parseEmailDraft(Long jobId, String aiResponse) {
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

        return new EmailDraft(null, jobId, subject, body, EmailStatus.PENDING, LocalDateTime.now());
    }
}
