package com.juanperuzzo.job_hunter.application.service;

import java.util.List;
import java.util.Locale;

/**
 * Query-time work-model signal detection over persisted job text (title + description).
 *
 * <p>Scrapers parse an exclusive {@code workModel} per job on ingest
 * (InfoJobs: "Home office" | "Híbrido" | "Presencial"; Gupy/LinkedIn text signals),
 * but that value is not persisted, so the opt-in {@code remoteOnly} listing filter
 * re-detects the signal from text at query time. The vocabulary mirrors the provider
 * labels plus the broader remote terms already used by the normalizer and the
 * preference scorer.
 *
 * <p>Detection is deliberately strict: only an explicit remote signal with no onsite,
 * hybrid or negation conflict matches. Silent/unknown text never matches, so the
 * filter never surfaces ambiguous jobs under {@code remoteOnly=true}.
 */
public final class WorkModelSignals {

    private static final List<String> REMOTE_TERMS = List.of(
            "remoto", "remota", "remotos", "remotas", "remotamente", "remote",
            "home office", "homeoffice", "teletrabalho", "todo brasil",
            "work from home", "fully remote", "anywhere", "qualquer lugar");
    private static final List<String> REMOTE_NEGATION_TERMS = List.of(
            "não é remoto", "nao e remoto", "não é home office", "nao e home office",
            "not a remote position", "not remote");
    private static final List<String> ONSITE_TERMS = List.of(
            "presencial", "onsite", "on-site");
    private static final List<String> HYBRID_TERMS = List.of(
            "hibrido", "híbrido", "hybrid");

    private WorkModelSignals() {
    }

    /**
     * Returns true only when {@code text} carries an explicit remote signal and no
     * conflicting onsite, hybrid or negation signal. Blank or null text never matches.
     */
    public static boolean isRemoteOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean remote = containsAny(normalized, REMOTE_TERMS)
                && !containsAny(normalized, REMOTE_NEGATION_TERMS);
        return remote && !containsAny(normalized, ONSITE_TERMS) && !containsAny(normalized, HYBRID_TERMS);
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }
}