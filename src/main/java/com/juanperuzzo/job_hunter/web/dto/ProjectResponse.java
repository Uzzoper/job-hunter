package com.juanperuzzo.job_hunter.web.dto;

import java.util.List;

public record ProjectResponse(
    String name,
    String description,
    List<String> techStack
) {}
