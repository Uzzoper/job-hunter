package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic preferences→scoring modifier applied to the raw AI match score
 * before persistence (spec: {@code docs/specs/preferences-scoring.md}).
 *
 * <p>Guarantees three things the AI alone cannot: (1) an explicit work-model
 * conflict <em>always</em> lowers the stored score by a fixed, documented
 * amount; (2) a seniority mismatch (role marked as pleno / "pl." / "PL" /
 * mid-level) <em>always</em> lowers it by a fixed amount, while never blocking;
 * (3) an excluded company <em>always</em> caps the score at 15, regardless of
 * the model's output. Absent or semantically blank preferences leave the raw
 * score untouched (byte-identical behavior).
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
    /**
     * A seniority mismatch for a junior-seeker: a job title explicitly marked as
     * pleno / "pl." / "PL" / mid-level. Unlike the excluded-company cap, this only
     * lowers the score — it never blocks the job from ranking or applying.
     */
    private static final int SENIORITY_MISMATCH_PENALTY = 10;

    /**
     * Whole-word markers for a pleno/mid-level title, built with the same
     * word-like-boundary idiom as {@link WorkModelSignals} (no letter/digit
     * immediately before or after). The lookarounds make "pl" inside a longer
     * word (e.g. "aplicação") or the "pl" prefix of "pleno" false-positive-free.
     */
    private static final List<Pattern> SENIORITY_MISMATCH_PATTERNS = List.of(
            compileWordPattern("pleno"),
            compileWordPattern("pl"),
            compileWordPattern("mid-level"));

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
        int modifier = workModelModifier(job.description(), preferences.workPreference())
                + seniorityModifier(job.title());
        return Math.max(0, Math.min(100, rawScore + modifier));
    }

    /**
     * Seniority fit modifier for a junior-seeker: roles explicitly marked as
     * pleno / "pl." / "PL" / mid-level lose {@value #SENIORITY_MISMATCH_PENALTY}
     * points. Junior and unmarked titles are neutral. Detection runs on the job
     * title only (word-level, case-insensitive) and never blocks the job — it
     * simply composes with the work-model modifier inside the 0–100 clamp.
     */
    private static int seniorityModifier(String title) {
        if (title == null) {
            return 0;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        boolean mismatch = SENIORITY_MISMATCH_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(normalized).find());
        return mismatch ? -SENIORITY_MISMATCH_PENALTY : 0;
    }

    private static Pattern compileWordPattern(String term) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])");
    }

    /**
     * Deterministic work-model fit modifier (formula documented in
     * {@code docs/specs/preferences-scoring.md}).
     *
     * <p>Signal detection delegates to the single shared vocabulary in
     * {@link WorkModelSignals}. Only <em>explicit</em> signals are penalized:
     * silent/unknown descriptions never lose points. The negation guard clears
     * the remote signal for "não é remoto" / "not remote" phrasing, so
     * "100% presencial — não é remoto" is detected as onsite.
     */
    private static int workModelModifier(String description, WorkPreference workPreference) {
        if (description == null || workPreference == null) {
            return 0;
        }
        String d = description.toLowerCase(Locale.ROOT);
        boolean r = WorkModelSignals.isRemote(description);
        boolean s = WorkModelSignals.isOnsite(description);
        boolean h = WorkModelSignals.isHybrid(description);

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