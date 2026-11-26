package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.List;
import java.util.Optional;

public class UserProfileService implements UserProfileUseCase {

    /**
     * Minimum accepted resume-text length — the single source the preview and
     * commit gates mirror (review 2026-11-15); the web bean validation on
     * {@code ProfileRequest.resumeText} carries the same number.
     */
    static final int RESUME_TEXT_MIN_LENGTH = 50;

    private final UserProfileRepository userProfileRepository;

    public UserProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public UserProfile getProfile() {
        return userProfileRepository.find()
                .orElse(new UserProfile(null, "Owner", null, "", List.of(), CompanyTone.STARTUP, List.of(),
                        null, null, null, null, null, null));
    }

    /**
     * Saves the profile, enforcing the resumeText minimum length. The profile id
     * of an existing record is preserved so the save is an update.
     *
     * @throws InvalidResumeTextException if the resume text is null or shorter
     *                                    than 50 characters
     */
    @Override
    public UserProfile saveProfile(UserProfile profile) {
        if (profile.resumeText() == null || profile.resumeText().length() < RESUME_TEXT_MIN_LENGTH) {
            throw new InvalidResumeTextException("Resume text must not be null and must be at least "
                    + RESUME_TEXT_MIN_LENGTH + " characters");
        }

        var existing = userProfileRepository.find();
        Long profileId = existing.map(UserProfile::id).orElse(null);

        var existingPrefs = existing.flatMap(p ->
                p.preferences() != null ? Optional.of(p.preferences()) : Optional.empty())
                .orElse(null);
        var effectivePrefs = profile.preferences() != null ? profile.preferences() : existingPrefs;

        var toSave = new UserProfile(profileId, profile.name(), profile.email(),
                profile.resumeText(), profile.skills(), profile.tone(), profile.projects(),
                profile.phone(), profile.contactEmail(), profile.portfolioUrl(),
                profile.githubUrl(), profile.linkedinUrl(), effectivePrefs);
        return userProfileRepository.save(toSave);
    }
}
