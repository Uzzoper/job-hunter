package com.juanperuzzo.job_hunter.application.port.in;

public record AuthResult(String token, Long userId, String name, String email) {}
