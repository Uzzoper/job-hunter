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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ResumeUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private final Path uploadDir;
    private final int maxAiChars;

    /**
     * @param aiPort               port for AI completions
     * @param userProfileService   service for profile persistence (includes validation)
     * @param userProfileRepository repository for reading existing profile tone
     * @param uploadDir            filesystem path for uploaded PDF storage
     * @param maxAiChars           maximum character count of resume text sent to the AI prompt
     */
    public ResumeUploadService(AiPort aiPort, UserProfileService userProfileService,
                               UserProfileRepository userProfileRepository,
                               String uploadDir,
                               int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.objectMapper = new ObjectMapper();
        this.uploadDir = Path.of(uploadDir);
        this.maxAiChars = maxAiChars;
    }

    /**
     * Uploads a PDF resume, extracts its text via PDFBox, sends the text to an AI
     * for skills/projects extraction, saves the PDF to disk, and persists the profile.
     *
     * @param userId the authenticated user's ID
     * @param file   the uploaded PDF file (max 2MB, must be {@code application/pdf})
     * @return the saved {@link UserProfile} with extracted skills, projects, and raw text
     * @throws IllegalArgumentException if the file is null/empty/non-PDF, or the PDF
     *                                  contains no extractable text
     * @throws AiException             if the AI call fails or returns unparseable JSON
     * @throws com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException
     *                                  if the user does not exist
     */
    public UserProfile uploadResume(Long userId, MultipartFile file) {
        validateFile(file);

        String rawText = extractPdfText(file);

        if (rawText.isBlank()) {
            throw new IllegalArgumentException("PDF file contains no extractable text");
        }

        var extraction = extractWithAi(rawText);

        savePdfToDisk(userId, file);

        var existingProfile = userProfileRepository.findByUserId(userId);

        CompanyTone tone = existingProfile
                .map(UserProfile::tone)
                .orElse(CompanyTone.FORMAL);

        List<Project> projects = extraction.projects().stream()
                .map(p -> new Project(p.name(), p.description(), String.join(", ", p.techStack())))
                .toList();

        // Contact fields are user-supplied (spec v1.1) — an AI-driven upload must
        // never wipe them, so existing values are carried over when present.
        var profile = new UserProfile(null, userId, rawText, extraction.skills(), tone, projects,
                existingProfile.map(UserProfile::phone).orElse(null),
                existingProfile.map(UserProfile::contactEmail).orElse(null),
                existingProfile.map(UserProfile::portfolioUrl).orElse(null),
                existingProfile.map(UserProfile::githubUrl).orElse(null),
                existingProfile.map(UserProfile::linkedinUrl).orElse(null));
        return userProfileService.saveProfile(userId, profile);
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
            // Parse as JsonNode tree to handle duplicate fields (qwen2.5:3b merges adjacent objects)
            var root = objectMapper.readTree(json);

            var skills = new ArrayList<String>();
            var skillsNode = root.get("skills");
            if (skillsNode != null && skillsNode.isArray()) {
                for (var skill : skillsNode) {
                    skills.add(skill.asText());
                }
            }

            var projects = new ArrayList<ResumeExtractionResponse.ExtractedProject>();
            var projectsNode = root.get("projects");
            if (projectsNode != null && projectsNode.isArray()) {
                for (var projectNode : projectsNode) {
                    var name = projectNode.has("name") ? projectNode.get("name").asText("") : "";
                    var description = projectNode.has("description") ? projectNode.get("description").asText("") : "";
                    var techStack = new ArrayList<String>();
                    var tsNode = projectNode.get("techStack");
                    if (tsNode != null) {
                        if (tsNode.isArray()) {
                            for (var t : tsNode) {
                                techStack.add(t.asText());
                            }
                        } else if (tsNode.isTextual()) {
                            techStack.addAll(List.of(tsNode.asText().split("\\s*,\\s*")));
                        }
                    }
                    projects.add(new ResumeExtractionResponse.ExtractedProject(name, description, techStack));
                }
            }

            return new ResumeExtractionResponse(skills, projects);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse AI extraction response. Raw: {}", response, e);
            throw new AiException("Failed to parse AI extraction: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String rawText) {
        var textForAi = rawText;
        if (rawText.length() > maxAiChars) {
            log.warn("Resume text is {} chars, truncating to {} for AI prompt", rawText.length(), maxAiChars);
            textForAi = rawText.substring(0, maxAiChars) + "...";
        }
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
""".formatted(textForAi);
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
