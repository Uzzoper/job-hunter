package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;

import java.util.List;
import java.util.regex.Pattern;

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
 * <p>All free-text values (cities, company names) are sanitized by
 * {@link #sanitize} before interpolation: control characters (including
 * newlines, carriage returns, null bytes, ESC) are replaced with a single
 * space so a hostile value can never forge additional prompt lines inside the
 * "(authoritative)" block. This is the single trust boundary covering both
 * prompts at once. Pure logic, no framework dependencies.
 */
public final class PreferencesPromptFormatter {

    /** Matches any UNICODE control or format character (C0/C1, newlines, CR, null, ESC, etc.). */
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{C}");

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
                sanitize(workModel(preferences.workPreference())),
                salaryFloor(preferences.salaryFloor()),
                sanitize(companies(preferences.excludedCompanies())));
    }

    /**
     * Replaces every control character (newlines, carriage returns, null bytes,
     * ESC, and any other {@code \p{C}} Unicode control) with a single space.
     * Ensures free-text values render as one inert line and can never forge
     * additional instructions inside the prompt.
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return CONTROL_CHARS.matcher(value).replaceAll(" ");
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