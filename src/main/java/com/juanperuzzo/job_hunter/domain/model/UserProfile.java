package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;
import static java.util.Objects.requireNonNull;

public record UserProfile(
        Long id,
        Long userId,
        String resumeText,
        List<String> skills,
        CompanyTone tone,
        List<Project> projects,
        String phone,
        String contactEmail,
        String portfolioUrl,
        String githubUrl,
        String linkedinUrl
) {
    public UserProfile {
        requireNonNull(userId, "userId must not be null");
        requireNonNull(resumeText, "resumeText must not be null");
        requireNonNull(skills, "skills must not be null");
        requireNonNull(tone, "tone must not be null");
        requireNonNull(projects, "projects must not be null");
        phone = normalize(phone);
        contactEmail = normalize(contactEmail);
        portfolioUrl = normalize(portfolioUrl);
        githubUrl = normalize(githubUrl);
        linkedinUrl = normalize(linkedinUrl);
    }

    /**
     * Normalizes an optional contact field: null stays null, blank/whitespace
     * becomes null, any other value is trimmed.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
