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
 * mid-level) <em>always</em> lowers it by a fixed amount when an explicit work
 * model is set (a salary-only profile is never penalized — salaryFloor is a
 * prompt-only signal), while never blocking; (3) an excluded company
 * <em>always</em> caps the score at 15, regardless of the model's output.
 * Absent or semantically blank preferences leave the raw score untouched
 * (byte-identical behavior).
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
     * A stack mismatch for the candidacy: none of the profile skills appears in
     * the job's title or description (match-quality spec §4). Same magnitude as
     * the seniority penalty; composes additively inside the 0–100 clamp and
     * never blocks the job — ordering signal only.
     */
    private static final int STACK_MISMATCH_PENALTY = 10;

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
     * 0–100. The stack signal applies whenever the profile carries a non-blank
     * skill list — preferences or not. The work-model, seniority and
     * excluded-company signals only apply when preferences carry content; the
     * raw score passes through unchanged for null/blank preferences without
     * skills (byte-identical behavior).
     */
    public static int adjust(int rawScore, Job job, UserPreferences preferences, List<String> profileSkills) {
        int modifier = stackModifier(job, profileSkills);
        if (preferences == null || !preferences.hasContent()) {
            return clamp(rawScore + modifier);
        }
        if (isExcludedCompany(job.company(), preferences.excludedCompanies())) {
            return Math.min(rawScore, EXCLUDED_COMPANY_SCORE_CAP);
        }
        modifier += workModelModifier(job.description(), preferences.workPreference());
        // PR #80 review P0-3: the seniority penalty only composes when an
        // EXPLICIT work model is set. A salary-only profile must stay
        // byte-identical (salaryFloor is a prompt-only signal), so a senior
        // title must never silently penalize it.
        if (preferences.workPreference() != null) {
            modifier += seniorityModifier(job);
        }
        return clamp(rawScore + modifier);
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Soft stack-fit modifier: −10 when none of the profile skills appears in
     * the normalized {@code title + " " + description} haystack. Skills are
     * trimmed; blank/empty lists carry no penalty (identity guarantee). A skill
     * hits when its normalized string is a substring of the haystack — the same
     * {@code contains()} idiom as {@link #mentionsAnyCity}. Independent of
     * {@code workPreference}: fires for any profile carrying skills.
     */
    private static int stackModifier(Job job, List<String> profileSkills) {
        if (profileSkills == null || profileSkills.isEmpty()) {
            return 0;
        }
        List<String> skills = profileSkills.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (skills.isEmpty()) {
            return 0;
        }
        String haystack = (job.title() == null ? "" : job.title().toLowerCase(Locale.ROOT))
                + " " + (job.description() == null ? "" : job.description().toLowerCase(Locale.ROOT));
        boolean hit = skills.stream()
                .anyMatch(skill -> haystack.contains(skill.toLowerCase(Locale.ROOT)));
        return hit ? 0 : -STACK_MISMATCH_PENALTY;
    }

    /**
     * Seniority fit modifier for a junior-seeker: roles explicitly marked as
     * pleno / "pl." / "PL" / mid-level lose {@value #SENIORITY_MISMATCH_PENALTY}
     * points, and so do descriptions requiring a bare 3..30 years of
     * experience near the "anos de experiência" / "years of experience"
     * marker (match-quality spec §3). Junior and unmarked roles are neutral.
     * Detection composes as 0 or −10 total: a title-hit OR a body-hit fires,
     * never both. Unlike the body signal, the word-level patterns stay
     * title-only — a free-form "pleno" in prose is not treated as a signal.
     *
     * <p>Callers only invoke this when an explicit {@link WorkPreference} is
     * set: the penalty belongs to the work-model dimension, and a salary-only
     * profile must remain byte-identical (prompt-only guarantee).
     */
    private static int seniorityModifier(Job job) {
        boolean mismatch = titleSignal(job.title())
                || bodyExperienceSignal(job.description());
        return mismatch ? -SENIORITY_MISMATCH_PENALTY : 0;
    }

    private static boolean titleSignal(String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        return SENIORITY_MISMATCH_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    /**
     * Years-of-experience signal in free-form description text. Fires when a
     * bare integer between 3 and 30 appears within ±6 tokens of the PT/EN
     * "years of experience" marker (both orders allowed). The marker is
     * matched as the consecutive token triple "anos de experiência" /
     * "anos de experiencia" / "years of experience"; anything else in the
     * text — including a bare "pleno" — is ignored (match-quality spec §3).
     */
    private static boolean bodyExperienceSignal(String description) {
        if (description == null) {
            return false;
        }
        String[] tokens = description.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (int i = 0; i < tokens.length; i++) {
            if (!isExperienceMarker(tokens, i)) {
                continue;
            }
            int from = Math.max(0, i - 6);
            int to = Math.min(tokens.length - 1, i + 2 + 6);
            for (int j = from; j <= to; j++) {
                Integer years = parseBareInteger(tokens[j]);
                if (years != null && years >= 3 && years <= 30) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isExperienceMarker(String[] tokens, int i) {
        if (i + 2 >= tokens.length) {
            return false;
        }
        return tokens[i].equals("anos") && tokens[i + 1].equals("de")
                        && (tokens[i + 2].equals("experiência") || tokens[i + 2].equals("experiencia"))
                || tokens[i].equals("years") && tokens[i + 1].equals("of")
                        && tokens[i + 2].equals("experience");
    }

    private static Integer parseBareInteger(String token) {
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.valueOf(token);
        }
        return null;
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