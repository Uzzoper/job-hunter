package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

public record ProfileRequest(
    String resumeText,
    List<String> skills,
    CompanyTone tone
) {}
