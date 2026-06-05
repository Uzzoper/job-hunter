package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;
import static java.util.Objects.requireNonNull;

public record UserProfile(
        Long id,
        Long userId,
        String resumeText,
        List<String> skills,
        CompanyTone tone
) {
    public UserProfile {
        requireNonNull(userId, "userId must not be null");
        requireNonNull(resumeText, "resumeText must not be null");
        requireNonNull(skills, "skills must not be null");
        requireNonNull(tone, "tone must not be null");
    }
}
