package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

public record ProfileResponse(
    Long id,
    /**
     * Account display name (self-hosted: from the single profile row).
     */
    String name,
    /** Account login email from the {@code users} row — NOT the resume contactEmail. */
    String email,
    String resumeText,
    List<String> skills,
    CompanyTone tone,
    List<ProjectResponse> projects,
    String phone,
    String contactEmail,
    String portfolioUrl,
    String githubUrl,
    String linkedinUrl,
    PreferencesResponse preferences
) {}
