package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import java.net.URI;
import java.util.Locale;

/**
 * Shared URL normalization helpers used by {@link JobNormalizer},
 * {@link com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider} and
 * {@link com.juanperuzzo.job_hunter.infrastructure.scraper.enricher.CompanySiteEnricher}.
 *
 * <p>Three canonical forms:
 * <ul>
 *   <li>{@link #absolute(String, String)} — resolve a possibly-relative URL against a base
 *       and produce an absolute URL with no trailing slash (returns {@code null} when the
 *       input is blank).</li>
 *   <li>{@link #noTrailingSlash(String)} — trim a single trailing {@code /} from an absolute URL
 *       without resolving it.</li>
 *   <li>{@link #host(String)} — extract the lowercase host of a URL (used to classify
 *       portal versus corporate sites).</li>
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

    /**
     * Lowercase host of a URL ({@code https://techco.gupy.io} → {@code techco.gupy.io}),
     * or null when the URL cannot be parsed or has no host. Null-on-failure keeps callers
     * free to decide how to treat unparseable URLs.
     */
    public static String host(String url) {
        try {
            var uri = URI.create(url);
            var host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
