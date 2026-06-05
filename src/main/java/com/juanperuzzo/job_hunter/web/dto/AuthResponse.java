package com.juanperuzzo.job_hunter.web.dto;

public record AuthResponse(
    String token,
    Long userId,
    String name,
    String email
) {}
