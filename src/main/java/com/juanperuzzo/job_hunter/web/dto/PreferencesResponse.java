package com.juanperuzzo.job_hunter.web.dto;

import java.util.List;

/**
 * Read-only representation of user preferences exposed by {@code GET /api/profile}.
 * Null when no preferences are set.
 */
public record PreferencesResponse(
        WorkPreferenceDto workPreference,
        Integer salaryFloor,
        List<String> excludedCompanies
) {}