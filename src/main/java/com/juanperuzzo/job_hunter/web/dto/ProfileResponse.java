package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

public record ProfileResponse(
    Long id,
    Long userId,
    String resumeText,
    List<String> skills,
    CompanyTone tone
) {}
