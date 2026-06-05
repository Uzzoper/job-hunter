package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AiAnalysisService implements AnalyzeJobUseCase {

    private final AiPort aiPort;
    private final JobAnalysisRepository jobAnalysisRepository;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    public AiAnalysisService(AiPort aiPort, JobAnalysisRepository jobAnalysisRepository, UserProfileRepository userProfileRepository) {
        this.aiPort = aiPort;
        this.jobAnalysisRepository = jobAnalysisRepository;
        this.userProfileRepository = userProfileRepository;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public JobAnalysis analyze(Long userId, Job job) {
        if (job.description() == null || job.description().trim().isEmpty()) {
            throw new IllegalArgumentException("Job description must not be empty");
        }

        var profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ProfileNotConfiguredException("Please configure your resume and skills profile first"));

        try {
            String prompt = buildPrompt(job, profile);
            String response = aiPort.complete(prompt);
            JobAnalysis analysis = parseAnalysis(response);
            return jobAnalysisRepository.save(new JobAnalysis(
                    null, job.id(), userId, analysis.matchScore(),
                    analysis.matchedSkills(), analysis.missingSkills(),
                    analysis.companyTone(), analysis.summary()
            ));
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to analyze job: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Job job, UserProfile profile) {
        return """
            You are a career assistant specialized in technology.
            Analyze the following job listing against this candidate profile:

            Candidate profile:
            - Skills: %s
            - Tone preference: %s
            - Resume: %s

            Return ONLY a valid JSON object, with no markdown and no additional text.

            Response format:
            {
              "matchScore": <integer from 0 to 100>,
              "matchedSkills": ["skill the candidate has", "..."],
              "missingSkills": ["skill the candidate lacks", "..."],
              "companyTone": "formal" | "casual" | "startup",
              "summary": "<one-line job summary, max 80 characters>"
            }

            matchScore criteria:
            - 80-100: candidate meets all main requirements
            - 60-79:  meets most requirements, minor gaps
            - 40-59:  meets about half the requirements
            - 20-39:  few requirements met, but potential exists
            - 0-19:   completely different stack

            companyTone criteria:
            - formal:  bank, consultancy, traditional company, serious language
            - startup: young company, casual language, words like "rockstar", "ninja"
            - casual:  middle ground, modern but professional company

            Job listing:
            Title: %s.
            Company: %s.
            Description: %s.
            """.formatted(
                String.join(", ", profile.skills()),
                profile.tone().name().toLowerCase(),
                profile.resumeText(),
                job.title(),
                job.company(),
                job.description());
    }

    private JobAnalysis parseAnalysis(String json) {
        try {
            if (json == null || json.isBlank()) {
                throw new AiException("AI returned empty or null response");
            }
            String cleaned = json.strip();
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*|```\\s*", "").strip();
            int jsonStart = cleaned.indexOf('{');
            int jsonEnd = cleaned.lastIndexOf('}');
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                throw new AiException("No valid JSON object found in AI response");
            }
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1).strip();
            JsonNode node = objectMapper.readTree(cleaned);

            int matchScore = node.get("matchScore").asInt(0);
            matchScore = Math.max(0, Math.min(100, matchScore));

            List<String> matchedSkills = objectMapper.convertValue(
                    node.get("matchedSkills"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (matchedSkills == null) matchedSkills = List.of();

            List<String> missingSkills = objectMapper.convertValue(
                    node.get("missingSkills"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (missingSkills == null) missingSkills = List.of();

            String toneStr = node.get("companyTone").asText("formal").toLowerCase();
            CompanyTone tone = switch (toneStr) {
                case "casual" -> CompanyTone.CASUAL;
                case "startup" -> CompanyTone.STARTUP;
                default -> CompanyTone.FORMAL;
            };

            String summary = node.get("summary").asText("");

            return new JobAnalysis(null, null, null, matchScore, matchedSkills, missingSkills, tone, summary);
        } catch (Exception e) {
            log.error("Failed to parse AI response. Raw response: {}", json, e);
            throw new AiException("Failed to parse AI response: " + e.getMessage(), e);
        }
    }
}
