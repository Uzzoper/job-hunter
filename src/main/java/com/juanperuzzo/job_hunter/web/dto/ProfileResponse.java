package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

public record ProfileResponse(
    Long id,
    Long userId,
    /**
     * Account display name from the {@code users} row (issue #92 slice a) —
     * distinct from resume-extracted contact data; filled on GET, {@code null}
     * on write routes until the slice-(b) preview/commit flow.
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
