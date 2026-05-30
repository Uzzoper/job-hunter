package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.List;

public class UserProfileService {

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
                .orElse(new UserProfile(null, userId, "", List.of(), CompanyTone.STARTUP));
    }

    public UserProfile saveProfile(Long userId, String resumeText, List<String> skills, CompanyTone tone) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        if (resumeText.length() < 50) {
            throw new IllegalArgumentException("Resume text must be at least 50 characters");
        }

        var existingProfile = userProfileRepository.findByUserId(userId);
        Long profileId = existingProfile.map(UserProfile::id).orElse(null);

        var profile = new UserProfile(profileId, userId, resumeText, skills, tone);
        return userProfileRepository.save(profile);
    }
}
