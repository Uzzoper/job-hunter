package com.juanperuzzo.job_hunter.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ProjectRequest(
    @NotBlank String name,
    @NotBlank String description,
    List<String> techStack
) {}
