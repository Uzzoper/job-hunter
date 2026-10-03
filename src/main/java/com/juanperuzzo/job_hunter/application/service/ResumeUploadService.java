package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumeExtractionResponse;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Pattern;

public class ResumeUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

    /** Contact field limits — mirror of {@code ProfileRequest} bean validation. */
    private static final int PHONE_MAX_LENGTH = 30;
    private static final int EMAIL_MAX_LENGTH = 255;
    private static final int URL_MAX_LENGTH = 500;
    /**
     * Pragmatic stand-in for Jakarta's {@code @Email}: requires a
     * {@code local@domain.tld} shape with no whitespace and a single "@".
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * Fields Prompt 3 must return as JSON arrays; {@code contact} is requested too but stays
     * optional (the prompt says "when present", {@code null} for anything not found).
     */
    private static final List<String> REQUIRED_FIELDS = List.of("skills", "projects");

    /**
     * Fields Prompt 3 requires on every extracted project ("Each project must have a name and
     * description"). {@code techStack} is not listed: the prompt allows it empty when not mentioned.
     */
    private static final List<String> REQUIRED_PROJECT_FIELDS = List.of("name", "description");

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

        // Fill-if-empty merge (docs/specs/profile-autofill-from-resume.md):
        // extracted contact values apply only to fields that are null/blank on
        // the stored profile — manual edits always win over AI extraction.
        var contact = extraction.contact();
        var stored = existingProfile.orElse(null);

        String phone = mergeContactField(stored == null ? null : stored.phone(),
                contact == null ? null : contact.phone(), "phone", PHONE_MAX_LENGTH, false);
        String contactEmail = mergeContactField(stored == null ? null : stored.contactEmail(),
                contact == null ? null : contact.email(), "contactEmail", EMAIL_MAX_LENGTH, true);
        String portfolioUrl = mergeContactField(stored == null ? null : stored.portfolioUrl(),
                contact == null ? null : contact.portfolioUrl(), "portfolioUrl", URL_MAX_LENGTH, false);
        String githubUrl = mergeContactField(stored == null ? null : stored.githubUrl(),
                contact == null ? null : contact.githubUrl(), "githubUrl", URL_MAX_LENGTH, false);
        String linkedinUrl = mergeContactField(stored == null ? null : stored.linkedinUrl(),
                contact == null ? null : contact.linkedinUrl(), "linkedinUrl", URL_MAX_LENGTH, false);

        var profile = new UserProfile(null, userId, rawText, extraction.skills(), tone, projects,
                phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl, null);
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
            // anchor on skills, the primary field of Prompt 3: reasoning blocks may quote
            // JSON of their own before the payload (see AiJsonPayloads)
            String json = AiJsonPayloads.lastObjectWithField(response, "skills");
            if (json == null) {
                throw new AiException("AI response contains no valid JSON");
            }
            // Parse as JsonNode tree to handle duplicate fields (qwen2.5:3b merges adjacent objects)
            var root = objectMapper.readTree(json);

            // Scenario 5 (resume-upload.md): a response lacking a mandatory field is an AI error
            // (502). Validated after selection on purpose — if the object the model actually
            // answered with lacks a field, that is the error case, and a scratch object quoted in a
            // reasoning block is never persisted as a silent fallback.
            var missing = missingRequiredFields(root);
            if (!missing.isEmpty()) {
                throw new AiException("AI response missing required fields: " + String.join(", ", missing));
            }

            // Scenario 5 again: a present mandatory field must have the contracted shape (an
            // array). Coercing "skills": "Java" or "projects": {} to an empty list would silently
            // overwrite whatever the profile already holds.
            var malformed = malformedRequiredFields(root);
            if (!malformed.isEmpty()) {
                throw new AiException("AI response has malformed required fields (arrays expected): "
                        + String.join(", ", malformed));
            }

            // One level deeper, same bug class: the items inside those arrays must respect Prompt 3
            // too. Coercing them with asText() would persist invented or empty data (a skill year,
            // a project with no description) over the real profile.
            var badItems = malformedArrayItems(root);
            if (!badItems.isEmpty()) {
                throw new AiException("AI response has malformed required field items: "
                        + String.join(", ", badItems));
            }

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

            return new ResumeExtractionResponse(skills, projects, parseContact(root));
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse AI extraction response. Raw: {}", response, e);
            throw new AiException("Failed to parse AI extraction: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the mandatory extraction fields that are absent from (or null in) {@code root},
     * in declaration order. Presence is what matters here: Prompt 3 allows an empty array when no
     * skill or project was found. A present field of an unexpected type is no longer lenient — it is
     * rejected one step later by {@link #malformedRequiredFields(JsonNode)}, and its items by
     * {@link #malformedArrayItems(JsonNode)}.
     */
    private static List<String> missingRequiredFields(JsonNode root) {
        return REQUIRED_FIELDS.stream()
                .filter(field -> root.get(field) == null || root.get(field).isNull())
                .toList();
    }

    /**
     * Returns the mandatory extraction fields present in {@code root} whose JSON type is not the
     * contracted array — {@code "skills": "Java"} or {@code "projects": {}}. Scenario 5 treats
     * those as an AI error like a missing field: the alternative is coercing them to empty lists,
     * which would silently overwrite real profile data.
     */
    private static List<String> malformedRequiredFields(JsonNode root) {
        return REQUIRED_FIELDS.stream()
                .filter(field -> root.get(field) != null && !root.get(field).isNull() && !root.get(field).isArray())
                .toList();
    }

    /**
     * Returns the offending item paths inside the mandatory arrays when they break Prompt 3's
     * contract: a non-string skill ({@code "skills": ["Java", 2020]}) or a project without the
     * {@code name}/{@code description} it must have. {@code techStack} is deliberately not checked
     * — the prompt allows it empty when not mentioned, so it stays tolerant as before.
     *
     * <p>Reported as a path ({@code projects[0].description}) so the failing model output can be
     * located without re-reading the response.
     */
    private static List<String> malformedArrayItems(JsonNode root) {
        var malformed = new ArrayList<String>();
        var skills = root.get("skills");
        if (skills != null && skills.isArray()) {
            for (int i = 0; i < skills.size(); i++) {
                if (!skills.get(i).isTextual()) {
                    malformed.add("skills[" + i + "]");
                }
            }
        }
        var projects = root.get("projects");
        if (projects != null && projects.isArray()) {
            for (int i = 0; i < projects.size(); i++) {
                var project = projects.get(i);
                var absent = REQUIRED_PROJECT_FIELDS.stream()
                        .filter(field -> !project.isObject() || isBlankText(project.get(field)))
                        .toList();
                if (!absent.isEmpty()) {
                    malformed.add("projects[" + i + "]." + String.join("+", absent));
                }
            }
        }
        return malformed;
    }

    /** True when {@code node} is textual and carries non-whitespace text. */
    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    /**
     * Parses the optional {@code contact} object tolerantly: absent, null, or
     * non-object nodes (string/array) are treated as "no contact data" and
     * yield {@code null} instead of a parse error.
     */
    private ResumeExtractionResponse.ExtractedContact parseContact(JsonNode root) {
        var contactNode = root.get("contact");
        if (contactNode == null || !contactNode.isObject()) {
            return null;
        }
        return new ResumeExtractionResponse.ExtractedContact(
                textOrNull(contactNode, "phone"),
                textOrNull(contactNode, "email"),
                textOrNull(contactNode, "portfolioUrl"),
                textOrNull(contactNode, "githubUrl"),
                textOrNull(contactNode, "linkedinUrl"));
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        var text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Fill-if-empty merge for a single contact field: an extracted value is
     * applied only when the stored value is null/blank; existing values always
     * win. An extracted value that fails the {@code PUT /api/profile} validation
     * rules is dropped with a warning instead of failing the upload.
     *
     * @param currentValue  value currently stored on the profile (may be null)
     * @param extractedValue value coming from the AI contact object (may be null)
     * @param fieldName      field name used in log messages
     * @param maxLength      maximum accepted length (mirrors {@code @Size})
     * @param requireEmailFormat when true, also enforces the email format rule
     */
    private static String mergeContactField(String currentValue, String extractedValue,
                                            String fieldName, int maxLength,
                                            boolean requireEmailFormat) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue;
        }
        if (extractedValue == null || extractedValue.isBlank()) {
            return null;
        }
        String candidate = extractedValue.trim();
        if (candidate.length() > maxLength
                || (requireEmailFormat && !EMAIL_PATTERN.matcher(candidate).matches())) {
            log.warn("Dropping invalid extracted contact field '{}' (fails PUT /api/profile validation)", fieldName);
            return null;
        }
        return candidate;
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
  ],
  "contact": {
    "phone": "<phone number or null>",
    "email": "<email address or null>",
    "portfolioUrl": "<portfolio URL or null>",
    "githubUrl": "<GitHub URL or null>",
    "linkedinUrl": "<LinkedIn URL or null>"
  }
}

Rules:
- skills: extract all technical skills (languages, frameworks, tools, databases)
- projects: extract personal, academic, and professional projects mentioned.
  Each project must have a name and description. techStack can be empty if not mentioned.
- contact: extract the candidate's contact details when present in the resume.
  Use null for any field that is not found. Never invent values.
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
