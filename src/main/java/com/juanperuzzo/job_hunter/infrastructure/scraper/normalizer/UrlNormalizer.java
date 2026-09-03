package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import java.net.URI;

/**
 * Shared URL normalization helpers used by {@link JobNormalizer} and
 * {@link com.juanperuzzo.job_hunter.infrastructure.scraper.enricher.CompanySiteEnricher}.
 *
 * <p>Two canonical forms:
 * <ul>
 *   <li>{@link #absolute(String, String)} — resolve a possibly-relative URL against a base
 *       and produce an absolute URL with no trailing slash (returns {@code null} when the
 *       input is blank).</li>
 *   <li>{@link #noTrailingSlash(String)} — trim a single trailing {@code /} from an absolute URL
 *       without resolving it.</li>
 * </ul>
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Resolve a possibly-relative URL to an absolute URL against {@code baseUrl} (no trailing slash). */
    public static String absolute(String url, String baseUrl) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String resolved;
            try {
                resolved = URI.create(baseUrl).resolve(url).toString();
            } catch (Exception e) {
                resolved = url.trim();
            }
            return noTrailingSlash(resolved);
        } catch (Exception e) {
            return url.trim();
        }
    }

    /** Remove a single trailing {@code /} from an absolute URL; returns {@code null} for blank input. */
    public static String noTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        var trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
