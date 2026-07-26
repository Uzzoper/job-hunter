package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
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

    private static final int MAX_RESUME_CHARS = 1500;
    private static final int MAX_DESCRIPTION_CHARS = 1000;

    private final AiPort aiPort;
    private final JobAnalysisRepository jobAnalysisRepository;
    private final UserProfileRepository userProfileRepository;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    public AiAnalysisService(AiPort aiPort, JobAnalysisRepository jobAnalysisRepository,
                             UserProfileRepository userProfileRepository, JobRepository jobRepository) {
        this.aiPort = aiPort;
        this.jobAnalysisRepository = jobAnalysisRepository;
        this.userProfileRepository = userProfileRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public JobAnalysis analyze(Long userId, Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + jobId));

        if (job.description() == null || job.description().trim().isEmpty()) {
            throw new IllegalArgumentException("Job description must not be empty");
        }

        var profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ProfileNotConfiguredException("Please configure your resume and skills profile first"));

        try {
            String prompt = buildPrompt(job, profile);
            String response = aiPort.complete(prompt);
            JobAnalysis parsed = parseAnalysis(response);
            var existingId = jobAnalysisRepository.findByJobIdAndUserId(job.id(), userId)
                    .map(JobAnalysis::id)
                    .orElse(null);
            var analysis = new JobAnalysis(
                    existingId, job.id(), userId, parsed.matchScore(),
                    parsed.matchedSkills(), parsed.missingSkills(),
                    parsed.companyTone(), parsed.summary()
            );
            return jobAnalysisRepository.save(analysis);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to analyze job: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Job job, UserProfile profile) {
        String resumeExcerpt = truncate(profile.resumeText(), MAX_RESUME_CHARS);
        String descExcerpt = truncate(job.description(), MAX_DESCRIPTION_CHARS);

        return """
            You are a career assistant. Analyze this job against the candidate.

            Candidate skills: %s
            Resume excerpt: %s

            Job: %s at %s
            Description: %s

            OUTPUT ONLY valid JSON. Start with { and end with }. No text before or after.

            {"matchScore": 75, "matchedSkills": ["Java"], "missingSkills": ["Go"], "companyTone": "formal", "summary": "Resume em ate 80 caracteres"}

            Score: 80-100=todos requisitos, 50-79=maioria, <50=poucos matches
            Tone: formal=tradicional, casual=moderno, startup=jovem/dinamico
            """.formatted(
                String.join(", ", profile.skills()),
                resumeExcerpt,
                job.title(),
                job.company(),
                descExcerpt);
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    private JobAnalysis parseAnalysis(String json) {
        try {
            if (json == null || json.isBlank()) {
                throw new AiException("AI returned empty or null response");
            }
            String cleaned = json.strip();
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*|```\\s*", "").strip();

            int jsonStart = cleaned.indexOf('{');
            if (jsonStart == -1) {
                throw new AiException("No JSON object found in AI response");
            }
            String potentialJson = cleaned.substring(jsonStart).strip();

            int jsonEnd = potentialJson.lastIndexOf('}');
            if (jsonEnd == -1) {
                if (potentialJson.length() > 10) {
                    potentialJson = potentialJson + "}";
                    jsonEnd = potentialJson.length() - 1;
                } else {
                    throw new AiException("No valid JSON object found in AI response");
                }
            }
            potentialJson = potentialJson.substring(0, jsonEnd + 1);

            JsonNode node = objectMapper.readTree(potentialJson);

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
