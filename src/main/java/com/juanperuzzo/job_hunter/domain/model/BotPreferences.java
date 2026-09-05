package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Parsed representation of a Hermes bot memory file ({@code MEMORY.md} / {@code USER.md}).
 * <p>
 * Sections are delimited by {@code §} lines. Within each section, lines matching
 * {@code key: value} are extracted into {@link #keyValues}; the full section text
 * is preserved in {@link #rawSections}.
 *
 * @param keyValues  extracted key-value pairs (last value wins on duplicate keys)
 * @param rawSections all non-empty section texts, preserving file order
 */
public record BotPreferences(
        Map<String, String> keyValues,
        List<String> rawSections
) {
    public BotPreferences {
        keyValues = keyValues == null ? Map.of() : Map.copyOf(keyValues);
        rawSections = rawSections == null ? List.of() : List.copyOf(rawSections);
    }

    /**
     * Returns an empty {@code BotPreferences} (no key-values, no sections).
     */
    public static BotPreferences empty() {
        return new BotPreferences(Map.of(), List.of());
    }
}
