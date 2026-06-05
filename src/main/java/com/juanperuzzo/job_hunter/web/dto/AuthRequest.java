package com.juanperuzzo.job_hunter.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password
) {}
