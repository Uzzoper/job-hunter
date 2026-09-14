package com.juanperuzzo.job_hunter.application.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single shared work-model vocabulary and matcher, used by both
 * {@link JobPreferenceScorer} (score modifier) and the opt-in {@code remoteOnly}
 * listing filter (spec: {@code docs/specs/list-jobs-filter.md}).
 *
 * <p>Terms are restricted to the provider labels the scrapers emit (InfoJobs:
 * "Home office" | "Híbrido" | "Presencial"; Gupy/LinkedIn: "Remoto") plus the
 * scorer vocabulary. Matching folds diacritics first (NFD strip + lowercase) and
 * collapses whitespace, then anchors every term at word-like boundaries, so
 * accented and unaccented forms resolve to a single canonical term and embedded
 * spellings (e.g. "remoto" inside "remotamenteextra") never false-positive.
 * Terms are therefore declared only in their unaccented canonical form.
 *
 * <p>Deliberately strict: {@link #isRemoteOnly(String)} requires an explicit
 * remote signal with no onsite, hybrid or negation conflict — silent/unknown
 * text never matches, so the filter never surfaces ambiguous jobs.
 */
public final class WorkModelSignals {

    private static final List<String> REMOTE_TERMS = List.of(
            "remoto", "remota", "remotos", "remotas", "remotamente", "remote",
            "home office", "homeoffice", "work from home", "fully remote",
            "anywhere", "qualquer lugar");
    private static final List<String> REMOTE_NEGATION_TERMS = List.of(
            "nao e remoto", "nao remoto", "sem remoto",
            "nao e home office",
            "not a remote position", "not remote");
    private static final List<String> ONSITE_TERMS = List.of(
            "presencial", "onsite", "on-site");
    private static final List<String> HYBRID_TERMS = List.of(
            "hibrido", "hybrid");

    private static final List<Pattern> REMOTE_PATTERNS = compilePatterns(REMOTE_TERMS);
    private static final List<Pattern> REMOTE_NEGATION_PATTERNS = compilePatterns(REMOTE_NEGATION_TERMS);
    private static final List<Pattern> ONSITE_PATTERNS = compilePatterns(ONSITE_TERMS);
    private static final List<Pattern> HYBRID_PATTERNS = compilePatterns(HYBRID_TERMS);

    private WorkModelSignals() {
    }

    /** True when {@code text} carries a remote signal with no negation. */
    public static boolean isRemote(String text) {
        var normalized = normalize(text);
        return normalized != null
                && matchesAny(normalized, REMOTE_PATTERNS)
                && !matchesAny(normalized, REMOTE_NEGATION_PATTERNS);
    }

    /** True when {@code text} carries an onsite signal. */
    public static boolean isOnsite(String text) {
        var normalized = normalize(text);
        return normalized != null && matchesAny(normalized, ONSITE_PATTERNS);
    }

    /** True when {@code text} carries a hybrid signal. */
    public static boolean isHybrid(String text) {
        var normalized = normalize(text);
        return normalized != null && matchesAny(normalized, HYBRID_PATTERNS);
    }

    /**
     * True only when {@code text} has an explicit remote signal and no onsite,
     * hybrid or negation conflict. Blank or null text never matches.
     */
    public static boolean isRemoteOnly(String text) {
        return isRemote(text) && !isOnsite(text) && !isHybrid(text);
    }

    private static List<Pattern> compilePatterns(List<String> terms) {
        return terms.stream()
                .map(term -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])"))
                .toList();
    }

    private static boolean matchesAny(String text, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    /** Fold case + diacritics and collapse whitespace; returns null for blank text. */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return folded.replaceAll("\\s+", " ").trim();
    }
}
