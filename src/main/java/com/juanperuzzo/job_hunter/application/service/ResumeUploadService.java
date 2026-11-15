package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumeExtractionResponse;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public class ResumeUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final ResumeStorage resumeStorage;
    private final int maxAiChars;

    /**
     * @param aiPort               port for AI completions
     * @param userProfileService   service for profile persistence (includes validation)
     * @param userProfileRepository repository for reading existing profile tone
     * @param resumeStorage        storage port owning the `{uploadDir}/{userId}/resume.pdf`
     *                             path convention (issue #92 slice a)
     * @param maxAiChars           maximum character count of resume text sent to the AI prompt
     */
    public ResumeUploadService(AiPort aiPort, UserProfileService userProfileService,
                               UserProfileRepository userProfileRepository,
                               ResumeStorage resumeStorage,
                               int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.resumeStorage = resumeStorage;
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
        ResumeExtractionPipeline.validateFile(file);

        String rawText = ResumeExtractionPipeline.extractPdfText(file);

        if (rawText.isBlank()) {
            throw new IllegalArgumentException("PDF file contains no extractable text");
        }

        var extraction = ResumeExtractionPipeline.extractWithAi(rawText, aiPort, maxAiChars);

        storeResume(userId, file);

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

        String phone = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.phone(),
                contact == null ? null : contact.phone(), "phone",
                ResumeExtractionPipeline.PHONE_MAX_LENGTH, false);
        String contactEmail = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.contactEmail(),
                contact == null ? null : contact.email(), "contactEmail",
                ResumeExtractionPipeline.EMAIL_MAX_LENGTH, true);
        String portfolioUrl = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.portfolioUrl(),
                contact == null ? null : contact.portfolioUrl(), "portfolioUrl",
                ResumeExtractionPipeline.URL_MAX_LENGTH, false);
        String githubUrl = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.githubUrl(),
                contact == null ? null : contact.githubUrl(), "githubUrl",
                ResumeExtractionPipeline.URL_MAX_LENGTH, false);
        String linkedinUrl = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.linkedinUrl(),
                contact == null ? null : contact.linkedinUrl(), "linkedinUrl",
                ResumeExtractionPipeline.URL_MAX_LENGTH, false);

        var profile = new UserProfile(null, userId, rawText, extraction.skills(), tone, projects,
                phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl, null);
        return userProfileService.saveProfile(userId, profile);
    }

    /**
     * Stores the original PDF behind the {@link ResumeStorage} port. Storage
     * failures escape uniformly as {@link UncheckedIOException} (the adapter's
     * own failure type — issue #125 review, 2026-11-13): the port owns the
     * failure boundary, so upload callers see one type regardless of whether
     * the byte read or the disk write failed.
     */
    private void storeResume(Long userId, MultipartFile file) {
        try {
            resumeStorage.write(userId, file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file for storage", e);
        }
    }
}
