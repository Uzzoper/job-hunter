package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;

import java.util.List;

/**
 * Renders the user work-preference context injected into the AI prompts
 * (analysis + email generation). Spec: {@code docs/specs/preferences-scoring.md},
 * prompt documentation: {@code docs/specs/prompts.md}.
 *
 * <p>When the profile carries no meaningful preference ({@code null} or a
 * semantically blank object) {@link #block} returns an empty string and the
 * calling services omit the block entirely — the prompt stays byte-identical
 * to the pre-feature version.
 *
 * <p>Pure logic, no framework dependencies.
 */
public final class PreferencesPromptFormatter {

    private PreferencesPromptFormatter() {
    }

    /**
     * Returns {@code true} when the preferences object holds at least one
     * meaningful value and should be injected into prompts.
     */
    public static boolean hasContent(UserPreferences preferences) {
        return preferences != null && preferences.hasContent();
    }

    /**
     * Renders the neutral candidate-preferences block (no trailing newline,
     * no leading newline). Returns an empty string when there is nothing to inject.
     */
    public static String block(UserPreferences preferences) {
        if (!hasContent(preferences)) {
            return "";
        }
        return """
                Candidate preferences (authoritative):
                - Work model: %s
                - Salary floor: %s
                - Excluded companies (hard skip): %s""".formatted(
                workModel(preferences.workPreference()),
                salaryFloor(preferences.salaryFloor()),
                companies(preferences.excludedCompanies()));
    }

    private static String workModel(WorkPreference workPreference) {
        if (workPreference == null) {
            return "Not set";
        }
        return switch (workPreference) {
            case WorkPreference.Remote() -> "Remote — fully remote / anywhere";
            case WorkPreference.Hybrid(List<String> cities) -> "Hybrid — office in: " + String.join(", ", cities);
            case WorkPreference.Onsite(List<String> cities) -> "Onsite — office in: " + String.join(", ", cities);
        };
    }

    private static String salaryFloor(Integer floor) {
        return floor == null ? "Not set" : "R$ " + floor + " per month (BRL)";
    }

    private static String companies(List<String> list) {
        return list == null || list.isEmpty() ? "None" : String.join(", ", list);
    }
}