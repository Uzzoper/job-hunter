package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProfileRequest(
    @NotBlank(message = "resumeText is required")
    @Size(min = 50, message = "resumeText must be at least 50 characters")
    String resumeText,

    @NotEmpty(message = "skills must not be empty")
    List<@NotBlank String> skills,

    @NotNull(message = "tone is required")
    CompanyTone tone,

    List<@Valid ProjectRequest> projects,

    @Size(max = 30, message = "phone must be at most 30 characters")
    String phone,

    @Email(message = "contactEmail must be a valid email address")
    @Size(max = 255, message = "contactEmail must be at most 255 characters")
    String contactEmail,

    @Size(max = 500, message = "portfolioUrl must be at most 500 characters")
    String portfolioUrl,

    @Size(max = 500, message = "githubUrl must be at most 500 characters")
    String githubUrl,

    @Size(max = 500, message = "linkedinUrl must be at most 500 characters")
    String linkedinUrl
) {
    public ProfileRequest {
        if (projects == null) projects = List.of();
    }
}
