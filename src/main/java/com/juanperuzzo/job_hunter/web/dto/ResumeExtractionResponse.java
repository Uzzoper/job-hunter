package com.juanperuzzo.job_hunter.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ResumeExtractionResponse(
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("projects") List<ExtractedProject> projects
) {
    public record ExtractedProject(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("techStack") List<String> techStack
    ) {}
}
