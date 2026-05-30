package com.juanperuzzo.job_hunter.web.dto;

public record AuthRequest(
    String name,
    String email,
    String password
) {}
