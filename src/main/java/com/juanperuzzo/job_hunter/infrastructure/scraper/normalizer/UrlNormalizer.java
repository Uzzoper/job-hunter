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
     * <p>
     * {@link URI#getHost()} returns null for hosts containing an underscore (RFC 2396
     * registry-based authority fallback), so a manual extraction from the authority is used
     * as a fallback — otherwise portal URLs such as {@code https://bbc_digital.gupy.io/...}
     * would bypass the {@code PortalDomains} filter.
     */
    public static String host(String url) {
        try {
            var uri = URI.create(url);
            var host = uri.getHost();
            if (host != null) {
                return host.toLowerCase(Locale.ROOT);
            }
            return manualHost(url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fallback host extraction for URLs whose authority is not a valid RFC 2396 host
     * (e.g. contains an underscore): take the substring between {@code ://} and the next
     * {@code /}, then strip userinfo ({@code @}) and {@code :port}. Returns null when
     * nothing usable remains.
     */
    private static String manualHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        var schemeSep = url.indexOf("://");
        if (schemeSep < 0) {
            return null;
        }
        var start = schemeSep + 3;
        var end = url.indexOf('/', start);
        if (end < 0) {
            end = url.length();
        }
        var authority = url.substring(start, end);
        var at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        var colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        authority = authority.trim().toLowerCase(Locale.ROOT);
        return authority.isEmpty() ? null : authority;
    }
}
