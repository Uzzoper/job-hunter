package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumeExtractionResponse;
import com.juanperuzzo.job_hunter.application.dto.ResumePreview;
import com.juanperuzzo.job_hunter.application.port.in.AccountIdentity;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless resume preview (issue #92 slice b): extracts and returns what a
 * commit would write, computed against the stored profile — persists nothing
 * (the service has no storage or profile-writing dependency at all; abandoned
 * previews cost one extraction and nothing else).
 *
 * <p>{@code contact} is the fill-if-empty merge preview (docs/specs/
 * profile-autofill-from-resume.md — stored value wins over extraction); fields
 * still empty after the merge land in {@code missingFields} for the bot to ask
 * in chat. {@code digest} is the md5 of the uploaded bytes, echoed back at
 * commit so the confirmed artifact is provably the previewed one.
 */
public class ResumePreviewService {

    private static final Logger log = LoggerFactory.getLogger(ResumePreviewService.class);

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final int maxAiChars;

    public ResumePreviewService(AiPort aiPort, UserProfileService userProfileService,
                                UserProfileRepository userProfileRepository, int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.maxAiChars = maxAiChars;
    }

    /**
     * @param file   the uploaded PDF file (same validation as the upload flow)
     * @return the preview of what commit would write; nothing is persisted
     * @throws IllegalArgumentException if the file is null/empty/non-PDF, or the PDF
     *                                  contains no extractable text
     * @throws com.juanperuzzo.job_hunter.domain.exception.AiException
     *                                  if the AI call fails or returns unparseable JSON
     * @throws com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException
     *                                  if the extracted text is shorter than the
     *                                  {@link UserProfileService#RESUME_TEXT_MIN_LENGTH} gate
     * @throws com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException
     *                                  if the user does not exist
     */
    public ResumePreview preview(MultipartFile file) {
        ResumeExtractionPipeline.validateFile(file);

        String rawText = ResumeExtractionPipeline.extractPdfText(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("PDF file contains no extractable text");
        }
        // Same validity domain as saveProfile — the preview never shows a
        // result the commit would reject (review 2026-11-15).
        if (rawText.length() < UserProfileService.RESUME_TEXT_MIN_LENGTH) {
            throw new InvalidResumeTextException("Resume text must be at least "
                    + UserProfileService.RESUME_TEXT_MIN_LENGTH + " characters");
        }

        // Cheap reads before the AI call: an unknown user fails without
        // spending a completion (review 2026-11-15).
        // accountIdentity = users row (slice (a) source of truth)
        var stored = userProfileRepository.find().orElse(null);
        AccountIdentity identity = stored == null
                ? new AccountIdentity("Owner", null)
                : new AccountIdentity(stored.name(), stored.email());

        var extraction = ResumeExtractionPipeline.extractWithAi(rawText, aiPort, maxAiChars);
        var contact = extraction.contact();

        // Fill-if-empty merge preview — same rules the commit applies; nothing saved.
        String phone = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.phone(),
                contact == null ? null : contact.phone(), "phone",
                ResumeExtractionPipeline.PHONE_MAX_LENGTH, false);
        String contactEmail = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.contactEmail(),
                contact == null ? null : contact.email(), "contactEmail",
                ResumeExtractionPipeline.EMAIL_MAX_LENGTH, true);
        String portfolioUrl = ResumeExtractionPipeline.mergeContactField(stored == null ? null : stored.portfolioUrl(),
                contact == null ? null : contact.portfolioUrl(), "portfolioUrl",
                ResumeExtractionPipeline.URL_MAX_LENGTH, false);

        var missingFields = new ArrayList<String>();
        if (phone == null) missingFields.add("phone");
        if (contactEmail == null) missingFields.add("contactEmail");
        if (portfolioUrl == null) missingFields.add("portfolioUrl");

        // The tone commit would keep: stored value, else FORMAL (upload default).
        CompanyTone tone = stored == null ? CompanyTone.FORMAL : stored.tone();

        var projects = extraction.projects().stream()
                .map(p -> new ResumePreview.ProjectPreview(p.name(), p.description(), p.techStack()))
                .toList();

        log.info("Resume preview for user {}: {} skills, {} projects, {} missing fields (nothing persisted)",
                extraction.skills().size(), projects.size(), missingFields.size());

        return new ResumePreview(identity,
                new ResumePreview.ContactPreview(phone, contactEmail, portfolioUrl),
                extraction.skills(), projects, tone, rawText.length(),
                md5Hex(file), List.copyOf(missingFields));
    }

    private static String md5Hex(MultipartFile file) {
        try {
            return ResumeExtractionPipeline.md5Hex(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file for digest", e);
        }
    }
}
