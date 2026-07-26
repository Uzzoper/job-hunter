package com.juanperuzzo.job_hunter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectRequest(
    @NotBlank String name,
    @NotBlank String description,
    @NotBlank String techStack
) {}
