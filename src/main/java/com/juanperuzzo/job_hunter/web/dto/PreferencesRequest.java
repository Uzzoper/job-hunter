package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Optional preferences the user can send via {@code PUT /api/profile}.
 * <p>
 * When the entire object is omitted or null, existing preferences are preserved.
 * When present, all sub-fields are authoritative (human override).
 */
public record PreferencesRequest(
        WorkModel workModel,

        @Min(value = 1, message = "salaryFloor must be at least 1")
        @Max(value = 500_000, message = "salaryFloor must not exceed 500,000")
        Integer salaryFloor,

        @Size(max = 20, message = "locations must contain at most 20 items")
        List<@Size(max = 100, message = "each location must be at most 100 characters") String> locations,

        @Size(max = 50, message = "excludedCompanies must contain at most 50 items")
        List<@Size(max = 200, message = "each company name must be at most 200 characters") String> excludedCompanies
) {}
