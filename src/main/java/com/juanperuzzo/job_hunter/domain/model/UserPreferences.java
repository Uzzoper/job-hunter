package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;

/**
 * Value object holding a user's work-preference data.
 * <p>
 * All fields are nullable (null = unset). The compact constructor validates and
 * normalizes the data: lists are capped and null-coalesced, salary is bounds-checked.
 * This is the <em>validate-strict</em> half of the parse-lenient / validate-strict
 * firewall — raw bot-mem strings are normalized <em>before</em> reaching this record.
 *
 * @param workModel          preferred work model (nullable)
 * @param salaryFloor        desired minimum salary in BRL (nullable; must be 1–500,000)
 * @param locations          preferred locations (null → empty; max 20 items ≤ 100 chars each)
 * @param excludedCompanies  companies to skip (null → empty; max 50 items ≤ 200 chars each)
 */
public record UserPreferences(
        WorkModel workModel,
        Integer salaryFloor,
        List<String> locations,
        List<String> excludedCompanies
) {
    public static final int MAX_LOCATIONS = 20;
    public static final int MAX_LOCATION_LENGTH = 100;
    public static final int MAX_EXCLUDED_COMPANIES = 50;
    public static final int MAX_COMPANY_NAME_LENGTH = 200;
    public static final int MAX_SALARY_FLOOR = 500_000;

    public UserPreferences {
        if (salaryFloor != null && salaryFloor <= 0) {
            throw new IllegalArgumentException("salaryFloor must be positive, got: " + salaryFloor);
        }
        if (salaryFloor != null && salaryFloor > MAX_SALARY_FLOOR) {
            throw new IllegalArgumentException("salaryFloor exceeds sanity cap of " + MAX_SALARY_FLOOR + ", got: " + salaryFloor);
        }
        locations = normalizeList(locations, MAX_LOCATIONS, MAX_LOCATION_LENGTH, "location");
        excludedCompanies = normalizeList(excludedCompanies, MAX_EXCLUDED_COMPANIES, MAX_COMPANY_NAME_LENGTH, "company name");
    }

    /**
     * Returns an empty {@code UserPreferences} (all fields null/empty).
     */
    public static UserPreferences empty() {
        return new UserPreferences(null, null, List.of(), List.of());
    }

    /**
     * Normalizes a list field: null → empty list, trimmed items, cap enforced.
     * <p>
     * Deterministic cap: length validation runs on ALL items BEFORE the cap is
     * applied, so a length violation throws regardless of item position (an item
     * beyond the cap is never silently accepted just because the cap was reached
     * first — stream short-circuiting is avoided by collecting before capping).
     */
    private static List<String> normalizeList(List<String> raw, int maxItems, int maxLength, String fieldName) {
        if (raw == null) {
            return List.of();
        }
        var normalized = raw.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();

        // Validate lengths on ALL items before capping (deterministic ordering)
        for (String item : normalized) {
            if (item.length() > maxLength) {
                throw new IllegalArgumentException(
                        fieldName + " item exceeds max length of " + maxLength + " chars: '" + item + "'");
            }
        }

        return normalized.size() <= maxItems ? normalized : normalized.subList(0, maxItems);
    }
}
