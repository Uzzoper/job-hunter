package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;

/**
 * Value object holding a user's work-preference data.
 * <p>
 * All fields are nullable (null = unset). The compact constructor validates and
 * normalizes the data: lists are capped and null-coalesced, salary is bounds-checked.
 * This is the <em>validate-strict</em> half of the parse-lenient / validate-strict
 * firewall — raw bot-mem strings are normalized <em>before</em> reaching this record.
 * <p>
 * The work-location dimension is a single nullable {@link WorkPreference} sum
 * type — a Remote/Hybrid/Onsite choice. The bare {@code locations} list and the
 * loose {@code WorkModel} enum were removed in the #50 evolution because they
 * allowed contradictory states (e.g. {@code REMOTE} + a city list, or hybrid
 * without cities).
 *
 * @param workPreference     preferred work location model (nullable)
 * @param salaryFloor        desired minimum salary in BRL (nullable; must be 1–500,000)
 * @param excludedCompanies  companies to skip (null → empty; max 50 items ≤ 200 chars each)
 */
public record UserPreferences(
        WorkPreference workPreference,
        Integer salaryFloor,
        List<String> excludedCompanies
) {
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
        excludedCompanies = normalizeList(excludedCompanies, MAX_EXCLUDED_COMPANIES, MAX_COMPANY_NAME_LENGTH, "company name");
    }

    /**
     * Returns an empty {@code UserPreferences} (all fields null/empty).
     */
    public static UserPreferences empty() {
        return new UserPreferences(null, null, List.of());
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