package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

/**
 * Stateles resume preview response (issue #92 slice b): what a commit would
 * write, computed against the stored profile — nothing was persisted by the
 * call that produced it.
 */
public record ResumePreviewResponse(
        IdentityResponse accountIdentity,
        ContactResponse contact,
        List<String> skills,
        List<ProjectPreviewResponse> projects,
        CompanyTone tone,
        int resumeTextChars,
        String digest,
        List<String> missingFields
) {
    /** The users-row identity (name + login email) — slice (a) source of truth. */
    public record IdentityResponse(String name, String email) {}

    /** Fill-if-empty merge preview (stored value wins over extraction). */
    public record ContactResponse(String phone, String contactEmail, String portfolioUrl) {}

    public record ProjectPreviewResponse(String name, String description, List<String> techStack) {}
}
