package com.juanperuzzo.job_hunter.domain.model;

import java.util.List;

/**
 * Sealed sum type for a user's work-location preference.
 * <p>
 * The three variants make contradictory states <em>unrepresentable</em>:
 * <ul>
 *   <li>{@link Remote} — fully remote/anywhere; carries no city data.</li>
 *   <li>{@link Hybrid} — hybrid; requires at least one city.</li>
 *   <li>{@link Onsite} — on-site; requires at least one city.</li>
 * </ul>
 * There is no way to express "remote plus a city list" or "hybrid/onsite
 * without cities". The compact constructors enforce the invariants and the
 * compiler enforces exhaustiveness.
 * <p>
 * Pure Java — no Spring/framework dependencies. City caps follow the same
 * family as the old locations list: max 20 items, each max 100 chars, count
 * excess silently trimmed and length violations rejected deterministically
 * (all items validated before the cap).
 */
public sealed interface WorkPreference permits WorkPreference.Remote, WorkPreference.Hybrid, WorkPreference.Onsite {

    int MAX_CITIES = 20;
    int MAX_CITY_LENGTH = 100;

    /**
     * Fully remote preference. No city data — a remote worker has no city constraint.
     */
    record Remote() implements WorkPreference {
    }

    /**
     * Hybrid preference — work some days on-site, some days remote.
     *
     * @param cities the cities where the user is willing to work on-site
     *               (≥ 1 non-blank city; max 20 items, each ≤ 100 chars)
     */
    record Hybrid(List<String> cities) implements WorkPreference {
        public Hybrid {
            cities = normalizeCities(cities);
        }
    }

    /**
     * On-site preference — work requires physical presence.
     *
     * @param cities the cities where the user is willing to work on-site
     *               (≥ 1 non-blank city; max 20 items, each ≤ 100 chars)
     */
    record Onsite(List<String> cities) implements WorkPreference {
        public Onsite {
            cities = normalizeCities(cities);
        }
    }

    /**
     * Normalizes a city list for {@link Hybrid}/{@link Onsite}: trims items,
     * drops null/blank entries, validates all lengths BEFORE the count cap
     * (deterministic), and requires at least one non-blank city.
     *
     * @throws IllegalArgumentException if the list is null, empty, or all-blank,
     *                                  or if any city exceeds {@link #MAX_CITY_LENGTH}
     */
    private static List<String> normalizeCities(List<String> cities) {
        if (cities == null) {
            throw new IllegalArgumentException("cities must not be null — hybrid/onsite require at least one city");
        }
        var normalized = cities.stream()
                .filter(city -> city != null && !city.isBlank())
                .map(String::trim)
                .toList();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("cities must contain at least one non-blank city");
        }

        // Validate lengths on ALL items before capping (deterministic ordering)
        for (String city : normalized) {
            if (city.length() > MAX_CITY_LENGTH) {
                throw new IllegalArgumentException(
                        "city exceeds max length of " + MAX_CITY_LENGTH + " chars: '" + city + "'");
            }
        }

        return normalized.size() <= MAX_CITIES ? normalized : List.copyOf(normalized.subList(0, MAX_CITIES));
    }
}