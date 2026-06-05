package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
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
    CompanyTone tone
) {}
