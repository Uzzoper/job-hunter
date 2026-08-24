package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.UserProfileUseCase;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.exception.InvalidResumeTextException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.List;

public class UserProfileService implements UserProfileUseCase {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public UserProfileService(UserRepository userRepository, UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    public UserProfile getProfile(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        return userProfileRepository.findByUserId(userId)
                .orElse(new UserProfile(null, userId, "", List.of(), CompanyTone.STARTUP, List.of(),
                        null, null, null, null, null));
    }

    /**
     * Saves the profile for the given authenticated user, enforcing the user's
     * existence and the resumeText minimum length. The profile id of an existing
     * record is preserved so the save is an update, and {@code userId} is always
     * taken from the authenticated parameter — never from the payload.
     *
     * @throws InvalidResumeTextException if the resume text is null or shorter
     *                                    than 50 characters
     */
    @Override
    public UserProfile saveProfile(Long userId, UserProfile profile) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        if (profile.resumeText() == null || profile.resumeText().length() < 50) {
            throw new InvalidResumeTextException("Resume text must not be null and must be at least 50 characters");
        }

        Long profileId = userProfileRepository.findByUserId(userId).map(UserProfile::id).orElse(null);

        var toSave = new UserProfile(profileId, userId, profile.resumeText(), profile.skills(),
                profile.tone(), profile.projects(), profile.phone(), profile.contactEmail(),
                profile.portfolioUrl(), profile.githubUrl(), profile.linkedinUrl());
        return userProfileRepository.save(toSave);
    }
}
