package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;

public record JobAnalysis(
        int matchScore,
        List<String> matchedSkills,
        List<String> missingSkills,
        CompanyTone companyTone,
        String summary
) {
}
