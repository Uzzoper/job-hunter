package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.dto.ResumeCorrections;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ResumeStorage;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.exception.ResumeDigestMismatchException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Confirmed resume commit (issue #92 slice b): the commit call IS the explicit
 * confirmation — only it replaces the active resume/profile. Extraction is
 * re-run at commit (documented trade-off in the spec: no draft drift between
 * preview and commit, no temp-file lifecycle, no TOCTOU).
 *
 * <p>Merge order: chat correction &gt; stored value &gt; AI extraction — a chat
 * correction is an explicit user instruction and overrides; extraction keeps
 * losing to stored values (fill-if-empty preserved). When {@code corrections}
 * carries a digest, it must match the uploaded file's md5 — otherwise
 * {@link ResumeDigestMismatchException} and nothing is written.
 */
public class ResumeCommitService {

    private static final Logger log = LoggerFactory.getLogger(ResumeCommitService.class);

    private final AiPort aiPort;
    private final UserProfileService userProfileService;
    private final UserProfileRepository userProfileRepository;
    private final ResumeStorage resumeStorage;
    private final int maxAiChars;

    public ResumeCommitService(AiPort aiPort, UserProfileService userProfileService,
                               UserProfileRepository userProfileRepository,
                               ResumeStorage resumeStorage, int maxAiChars) {
        this.aiPort = aiPort;
        this.userProfileService = userProfileService;
        this.userProfileRepository = userProfileRepository;
        this.resumeStorage = resumeStorage;
        this.maxAiChars = maxAiChars;
    }

    /**
     * @param userId      the authenticated user's ID
     * @param file        the uploaded PDF file (same validation as the upload flow)
     * @param corrections optional chat corrections; when present, {@code digest}
     *                    must match the uploaded file's md5
     * @return the saved {@link UserProfile}
     * @throws IllegalArgumentException if the file is null/empty/non-PDF, the PDF
     *                                  contains no extractable text, or a correction fails validation
     * @throws ResumeDigestMismatchException if {@code corrections.digest} is present
     *                                  and does not match the uploaded bytes — nothing is written
     * @throws com.juanperuzzo.job_hunter.domain.exception.AiException
     *                                  if the AI call fails or returns unparseable JSON
     * @throws com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException
     *                                  if the user does not exist
     */
    public UserProfile commit(Long userId, MultipartFile file, ResumeCorrections corrections) {
        ResumeExtractionPipeline.validateFile(file);

        byte[] bytes = readBytes(file);

        // Digest guard first: fail fast on an artifact that was never previewed,
        // before spending an extraction or touching any state. Case-insensitive
        // on purpose — a hex digest echo may arrive uppercased (review 2026-11-15).
        if (corrections != null && hasText(corrections.digest())) {
            var actual = ResumeExtractionPipeline.md5Hex(bytes);
            var echoed = corrections.digest().trim();
            if (!actual.equalsIgnoreCase(echoed)) {
                throw new ResumeDigestMismatchException(
                        "Resume digest mismatch: the confirmed artifact is not the previewed one (echoed "
                                + echoed + ", uploaded " + actual + ")");
            }
        }

        // Chat corrections are validated before anything is extracted or written:
        // invalid explicit input must cost the caller one 400, not one AI call.
        validateCorrections(corrections);

        String rawText = ResumeExtractionPipeline.extractPdfText(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("PDF file contains no extractable text");
        }
        // Same validity domain as saveProfile — a resume the commit would reject
        // must fail BEFORE the PDF is stored, never replace it (review 2026-11-15).
        if (rawText.length() < UserProfileService.RESUME_TEXT_MIN_LENGTH) {
            throw new InvalidResumeTextException("Resume text must be at least "
                    + UserProfileService.RESUME_TEXT_MIN_LENGTH + " characters");
        }

        var extraction = ResumeExtractionPipeline.extractWithAi(rawText, aiPort, maxAiChars);

        var existingProfile = userProfileRepository.findByUserId(userId);
        var stored = existingProfile.orElse(null);
        var contact = extraction.contact();

        // Fill-if-empty merge (docs/specs/profile-autofill-from-resume.md):
        // stored value wins over AI extraction — unchanged rules.
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

        // Chat corrections override everything — explicit user instruction.
        if (corrections != null) {
            phone = applyCorrection(phone, corrections.phone(), "phone",
                    ResumeExtractionPipeline.PHONE_MAX_LENGTH, false);
            contactEmail = applyCorrection(contactEmail, corrections.contactEmail(), "contactEmail",
                    ResumeExtractionPipeline.EMAIL_MAX_LENGTH, true);
            portfolioUrl = applyCorrection(portfolioUrl, corrections.portfolioUrl(), "portfolioUrl",
                    ResumeExtractionPipeline.URL_MAX_LENGTH, false);
        }

        CompanyTone tone = stored == null ? CompanyTone.FORMAL : stored.tone();

        List<Project> projects = extraction.projects().stream()
                .map(p -> new Project(p.name(), p.description(), String.join(", ", p.techStack())))
                .toList();

        var profile = new UserProfile(null, userId, rawText, extraction.skills(), tone, projects,
                phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl, null);

        // Writes, in order: original PDF → profile → identity (identity last:
        // saveProfile already verified the user row exists).
        storeResume(userId, bytes);
        var saved = userProfileService.saveProfile(userId, profile);

        if (corrections != null && (hasText(corrections.name()) || hasText(corrections.email()))) {
            // blank = "no correction" — normalized here too, so the service's
            // blank-rejection can never fire after the writes (review 2026-11-15)
            userProfileService.updateIdentity(userId,
                    hasText(corrections.name()) ? corrections.name().trim() : null,
                    hasText(corrections.email()) ? corrections.email().trim() : null);
        }

        log.info("Resume committed for user {} (profile id {}, identity corrected: {})",
                userId, saved.id(), corrections != null && (hasText(corrections.name()) || hasText(corrections.email())));
        return saved;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file for commit", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Validates chat corrections against the {@code PUT /api/profile} rules
     * (length caps, email format) — mirrors the bean validation on the web DTO.
     * Blank/absent corrections are "no correction", not errors.
     */
    private static void validateCorrections(ResumeCorrections corrections) {
        if (corrections == null) {
            return;
        }
        // Identity corrections ride the same pre-pass: an invalid name/email
        // must answer 400 before the PDF is stored or the profile replaced,
        // not from inside updateIdentity after both writes (review 2026-11-15).
        validateCorrectionValue("name", corrections.name(), 255, false);
        validateCorrectionValue("email", corrections.email(), 255, true);
        validateCorrectionValue("phone", corrections.phone(), ResumeExtractionPipeline.PHONE_MAX_LENGTH, false);
        validateCorrectionValue("contactEmail", corrections.contactEmail(), ResumeExtractionPipeline.EMAIL_MAX_LENGTH, true);
        validateCorrectionValue("portfolioUrl", corrections.portfolioUrl(), ResumeExtractionPipeline.URL_MAX_LENGTH, false);
    }

    private static void validateCorrectionValue(String fieldName, String value, int maxLength, boolean requireEmailFormat) {
        if (!hasText(value)) {
            return;
        }
        var candidate = value.trim();
        if (candidate.length() > maxLength
                || (requireEmailFormat && !ResumeExtractionPipeline.EMAIL_PATTERN.matcher(candidate).matches())) {
            throw new IllegalArgumentException(
                    "Invalid correction for '" + fieldName + "' (fails PUT /api/profile validation)");
        }
    }

    /**
     * Applies one pre-validated chat correction on top of the merged value:
     * absent/blank → keep the merged value, otherwise the correction wins.
     */
    private static String applyCorrection(String mergedValue, String corrected, String fieldName,
                                          int maxLength, boolean requireEmailFormat) {
        if (!hasText(corrected)) {
            return mergedValue;
        }
        validateCorrectionValue(fieldName, corrected, maxLength, requireEmailFormat);
        return corrected.trim();
    }

    /**
     * Stores the original PDF behind the {@link ResumeStorage} port — same
     * failure contract as the upload flow ({@link UncheckedIOException}).
     */
    private void storeResume(Long userId, byte[] bytes) {
        resumeStorage.write(userId, bytes);
    }
}
