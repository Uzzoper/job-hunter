package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;

/**
 * Structured content of a resume tailored by AI for a specific job.
 * Pure domain record — no framework dependencies.
 */
public record TailoredResume(
        String objective,
        Skills skills,
        List<TailoredProject> projects,
        List<TailoredExperience> experience,
        List<TailoredEducation> education,
        List<String> courses,
        List<TailoredLanguage> languages,
        List<String> differentials
) {

    /**
     * Technical skills grouped by category, mirroring the resume sections.
     */
    public record Skills(
            List<String> languages,
            List<String> frameworks,
            List<String> databases,
            List<String> cloudDevOps,
            List<String> tools,
            List<String> concepts
    ) {
    }

    /**
     * A project entry with a name, bullet points, and an optional link.
     */
    public record TailoredProject(
            String name,
            List<String> bullets,
            String link
    ) {
    }

    /**
     * A professional experience entry with role, company, period, and bullets.
     */
    public record TailoredExperience(
            String role,
            String company,
            String period,
            List<String> bullets
    ) {
    }

    /**
     * An education entry with degree, institution, and status.
     */
    public record TailoredEducation(
            String degree,
            String institution,
            String status
    ) {
    }

    /**
     * A language entry with the language name and proficiency level.
     */
    public record TailoredLanguage(
            String language,
            String level
    ) {
    }
}