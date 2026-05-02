package com.juanperuzzo.job_hunter.web.dto;

import java.time.LocalDate;

public record JobResponse(
    Long id,
    String title,
    String company,
    String url,
    String description,
    LocalDate postedAt,
    Integer matchScore
) {}
