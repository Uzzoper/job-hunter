package com.juanperuzzo.job_hunter.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ResumeExtractionResponse(
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("projects") List<ExtractedProject> projects,
        @JsonProperty("contact") ExtractedContact contact
) {
    public record ExtractedProject(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("techStack") List<String> techStack
    ) {}

    public record ExtractedContact(
            @JsonProperty("phone") String phone,
            @JsonProperty("email") String email,
            @JsonProperty("portfolioUrl") String portfolioUrl,
            @JsonProperty("githubUrl") String githubUrl,
            @JsonProperty("linkedinUrl") String linkedinUrl
    ) {}
}
