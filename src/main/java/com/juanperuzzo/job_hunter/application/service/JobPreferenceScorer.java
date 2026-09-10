package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic preferences→scoring modifier applied to the raw AI match score
 * before persistence (spec: {@code docs/specs/preferences-scoring.md}).
 *
 * <p>Guarantees two things the AI alone cannot: (1) an explicit work-model
 * conflict <em>always</em> lowers the stored score by a fixed, documented
 * amount; (2) an excluded company <em>always</em> caps the score at 15,
 * regardless of the model's output. Absent or semantically blank preferences
 * leave the raw score untouched (byte-identical behavior).
 *
 * <p>Pure logic, no framework dependencies. Nothing here reads or writes user
 * data outside the supplied arguments.
 */
public final class JobPreferenceScorer {

    /** A full contradiction (remote-pref vs explicit onsite ad, and the reverse). */
    private static final int ONSITE_CONFLICT_PENALTY = 15;
    /** A partial conflict (hybrid requires some presence; remote ad in a preferred city). */
    private static final int HYBRID_CONFLICT_PENALTY = 8;
    /** Hard skip for excluded companies — below the 60 template threshold and the "majority fit" AI band. */
    private static final int EXCLUDED_COMPANY_SCORE_CAP = 15;

    private static final List<String> REMOTE_TERMS = List.of(
            "remoto", "remota", "remotos", "remotas", "remote", "remotamente",
            "home office", "homeoffice", "work from home", "fully remote",
            "anywhere", "qualquer lugar");
    private static final List<String> REMOTE_NEGATION_TERMS = List.of(
            "não é remoto", "nao e remoto", "não é home office", "nao e home office",
            "not a remote position", "not remote");
    private static final List<String> ONSITE_TERMS = List.of(
            "presencial", "onsite", "on-site");
    private static final List<String> HYBRID_TERMS = List.of(
            "hibrido", "híbrido", "hybrid");

    private JobPreferenceScorer() {
    }

    /**
     * Returns the preference-adjusted score for {@code rawScore}, clamped to
     * 0–100. The raw score passes through unchanged when preferences are null
     * or semantically blank.
     */
    public static int adjust(int rawScore, Job job, UserPreferences preferences) {
        if (preferences == null || !preferences.hasContent()) {
            return rawScore;
        }
        if (isExcludedCompany(job.company(), preferences.excludedCompanies())) {
            return Math.min(rawScore, EXCLUDED_COMPANY_SCORE_CAP);
        }
        int modifier = workModelModifier(job.description(), preferences.workPreference());
        return Math.max(0, Math.min(100, rawScore + modifier));
    }

    /**
     * Deterministic work-model fit modifier (formula documented in
     * {@code docs/specs/preferences-scoring.md}).
     *
     * <p>Only <em>explicit</em> signals are penalized: silent/unknown
     * descriptions never lose points. The Remote negation guard clears the
     * remote signal for "não é remoto" / "not remote" phrasing, so
     * "100% presencial — não é remoto" is detected as onsite.
     */
    private static int workModelModifier(String description, WorkPreference workPreference) {
        if (description == null || workPreference == null) {
            return 0;
        }
        String d = description.toLowerCase(Locale.ROOT);
        boolean r = containsAny(d, REMOTE_TERMS) && !containsAny(d, REMOTE_NEGATION_TERMS);
        boolean s = containsAny(d, ONSITE_TERMS);
        boolean h = containsAny(d, HYBRID_TERMS);

        return switch (workPreference) {
            case WorkPreference.Remote() -> remoteModifier(r, s, h);
            case WorkPreference.Hybrid(List<String> cities) -> hybridModifier(r, s, h, cities, d);
            case WorkPreference.Onsite(List<String> cities) -> onsiteModifier(r, s, h, cities, d);
        };
    }

    private static int remoteModifier(boolean r, boolean s, boolean h) {
        if (s && !r) {
            return -ONSITE_CONFLICT_PENALTY;
        }
        if (h && !s) {
            return -HYBRID_CONFLICT_PENALTY;
        }
        return 0;
    }

    private static int hybridModifier(boolean r, boolean s, boolean h, List<String> cities, String d) {
        // Pure-remote ad without any preferred city: presence expected but impossible.
        if (r && !h && !s && !mentionsAnyCity(d, cities)) {
            return -HYBRID_CONFLICT_PENALTY;
        }
        return 0;
    }

    private static int onsiteModifier(boolean r, boolean s, boolean h, List<String> cities, String d) {
        boolean city = mentionsAnyCity(d, cities);
        if (r && !h && !s) {
            // Remote ad: full conflict when no preferred city is available, mild when the office sits in one.
            return city ? -HYBRID_CONFLICT_PENALTY : -ONSITE_CONFLICT_PENALTY;
        }
        return 0;
    }

    private static boolean mentionsAnyCity(String description, List<String> cities) {
        return cities.stream().anyMatch(city -> description.contains(city.toLowerCase(Locale.ROOT)));
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private static boolean isExcludedCompany(String company, List<String> excludedCompanies) {
        if (company == null || excludedCompanies.isEmpty()) {
            return false;
        }
        String normalized = company.trim().toLowerCase(Locale.ROOT);
        return excludedCompanies.stream()
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }
}