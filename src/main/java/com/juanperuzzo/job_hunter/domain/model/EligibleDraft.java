package com.juanperuzzo.job_hunter.domain.model;

public record EligibleDraft(
        EmailDraft draft,
        int matchScore,
        String company,
        String contactEmail,
        String jobTitle
) {}
