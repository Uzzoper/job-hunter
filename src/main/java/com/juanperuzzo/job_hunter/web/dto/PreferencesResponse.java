package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import java.util.List;

/**
 * Read-only representation of user preferences exposed by {@code GET /api/profile}.
 * Null when no preferences are set.
 */
public record PreferencesResponse(
        WorkModel workModel,
        Integer salaryFloor,
        List<String> locations,
        List<String> excludedCompanies
) {}
