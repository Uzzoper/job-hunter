package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.web.dto.ResumeExtractionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ResumeUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

    private final AiPort aiPort;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private final Path uploadDir;

    public ResumeUploadService(AiPort aiPort, UserProfileRepository userProfileRepository,
                               @Value("${app.upload-dir}") String uploadDir) {
        this.aiPort = aiPort;
        this.userProfileRepository = userProfileRepository;
        this.objectMapper = new ObjectMapper();
        this.uploadDir = Path.of(uploadDir);
    }

    public UserProfile uploadResume(Long userId, MultipartFile file) {
        validateFile(file);

        String rawText = extractPdfText(file);

        if (rawText.isBlank()) {
            throw new IllegalArgumentException("PDF file contains no extractable text");
        }

        var extraction = extractWithAi(rawText);

        savePdfToDisk(userId, file);

        var existingProfile = userProfileRepository.findByUserId(userId);
        CompanyTone tone = existingProfile.map(UserProfile::tone).orElse(CompanyTone.FORMAL);
        Long profileId = existingProfile.map(UserProfile::id).orElse(null);

        List<Project> projects = extraction.projects().stream()
                .map(p -> new Project(p.name(), p.description(), String.join(", ", p.techStack())))
                .toList();

        var profile = new UserProfile(profileId, userId, rawText, extraction.skills(), tone, projects);
        return userProfileRepository.save(profile);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new IllegalArgumentException("Only PDF files are accepted");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("File must have .pdf extension");
        }
    }

    private String extractPdfText(MultipartFile file) {
        try (var inputStream = file.getInputStream();
             PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read PDF file: " + e.getMessage(), e);
        }
    }

    private ResumeExtractionResponse extractWithAi(String rawText) {
        var prompt = buildPrompt(rawText);
        String response;
        try {
            response = aiPort.complete(prompt);
        } catch (Exception e) {
            throw new AiException("AI extraction failed: " + e.getMessage(), e);
        }

        try {
            String cleaned = response.strip();
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*|```\\s*", "").strip();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1) {
                throw new AiException("AI response contains no valid JSON");
            }
            String json = cleaned.substring(start, end + 1);
            return objectMapper.readValue(json, ResumeExtractionResponse.class);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse AI extraction response. Raw: {}", response, e);
            throw new AiException("Failed to parse AI extraction: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String rawText) {
        return """
You are a career assistant that extracts structured data from resumes.

Extract the following fields from the resume text below.
Return ONLY a valid JSON object, with no markdown and no additional text.

Response format:
{
  "skills": ["skill 1", "skill 2", ...],
  "projects": [
    {
      "name": "<project name>",
      "description": "<short description, max 80 chars>",
      "techStack": ["tech 1", "tech 2", ...]
    }
  ]
}

Rules:
- skills: extract all technical skills (languages, frameworks, tools, databases)
- projects: extract personal, academic, and professional projects mentioned.
  Each project must have a name and description. techStack can be empty if not mentioned.
- If no skills are found, return an empty array.
- If no projects are found, return an empty array.

Resume text:
%s
""".formatted(rawText.length() > 3000 ? rawText.substring(0, 3000) + "..." : rawText);
    }

    private void savePdfToDisk(Long userId, MultipartFile file) {
        try {
            Path userDir = uploadDir.resolve(String.valueOf(userId));
            Files.createDirectories(userDir);
            Path target = userDir.resolve("resume.pdf");
            file.transferTo(target);
            log.info("Saved uploaded resume to {}", target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save uploaded file", e);
        }
    }
}
