package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import org.jsoup.Jsoup;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single, shared email-extraction pipeline used by the {@link JobNormalizer} and the
 * {@link com.juanperuzzo.job_hunter.infrastructure.scraper.enricher.CompanySiteEnricher}.
 *
 * <p>Pipeline (per the email-enrichment spec P0/P2):
 * {@code mailto:} links first (DOM order), then a regex over a de-obfuscated plain-text
 * copy, with the same filters (noreply/donotreply/no-reply/apply + placeholder domains)
 * applied in both passes. Behavior mirrors what was previously duplicated in each caller;
 * moving it here guarantees the two extractors stay in lock-step.
 */
public final class EmailExtractor {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final List<Pattern> EXCLUDED_EMAIL_PATTERNS = List.of(
            Pattern.compile("^noreply@", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^donotreply@", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^no-reply@", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^apply@", Pattern.CASE_INSENSITIVE));

    private static final List<String> PLACEHOLDER_DOMAINS = List.of(
            "example.com", "exemplo.com", "test.com", "domain.com",
            "yourdomain.com", "seuemail.com");

    private static final Pattern OBFUSCATED_AT_BRACKET = Pattern.compile("\\s*\\[at\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_AT_PARENTHESES = Pattern.compile("\\s*\\(at\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_AT_WORD = Pattern.compile("\\s+at\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT_BRACKET = Pattern.compile("\\s*\\[dot\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT_PARENTHESES = Pattern.compile("\\s*\\(dot\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_ARROBA_BRACKET = Pattern.compile("\\s*\\[arroba\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_ARROBA_PARENTHESES = Pattern.compile("\\s*\\(arroba\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF]");

    private EmailExtractor() {
    }

    /**
     * Extract a contact email from a title and/or description, following the P0 priority:
     * <ol>
     *   <li>{@code mailto:} links first (DOM order, title before description)</li>
     *   <li>regex over the decoded + parsed text, on a de-obfuscated copy (title before description)</li>
     * </ol>
     * Returns {@code null} if no valid email is found. Existing filters
     * (noreply/donotreply/no-reply/apply + placeholder domains) still apply.
     */
    public static String extract(String title, String description) {
        var mailto = extractMailto(title);
        if (mailto == null && description != null) {
            mailto = extractMailto(description);
        }
        if (mailto != null) {
            return mailto;
        }

        var candidate = extractFirstEmail(deobfuscate(toPlainText(title)));
        if (candidate == null && description != null) {
            candidate = extractFirstEmail(deobfuscate(toPlainText(description)));
        }
        return candidate;
    }

    /**
     * Extract a contact email from a single raw HTML document, using the same priority
     * as {@link #extract(String, String)} but over one payload. Returns {@code null} if none.
     */
    public static String extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        var mailto = extractMailto(html);
        if (mailto != null) {
            return mailto;
        }
        return extractFirstEmail(deobfuscate(toPlainText(html)));
    }

    /**
     * Scan {@code a[href^=mailto:]} anchors in DOM order and return the first
     * mailto recipient that passes {@link #isContactEmail(String)}.
     */
    private static String extractMailto(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        var doc = Jsoup.parse(html);
        for (var anchor : doc.select("a[href^=mailto:]")) {
            var href = anchor.attr("href");
            if (href == null || href.isBlank()) {
                continue;
            }

            var email = href.substring("mailto:".length()).trim();
            if (email.isEmpty()) {
                continue;
            }

            // Drop query params commonly appended to mailto (e.g. ?subject=...)
            var queryIndex = email.indexOf('?');
            if (queryIndex >= 0) {
                email = email.substring(0, queryIndex).trim();
            }

            if (isContactEmail(email)) {
                return email;
            }
        }
        return null;
    }

    /**
     * Reduce raw HTML to its visible text: decode entities first, then parse with Jsoup
     * so email-shaped strings inside attributes (e.g. CSS classes) are never matched.
     */
    private static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(JobNormalizer.decodeEntities(html)).text();
    }

    /**
     * Normalize a text copy for extraction: turn common obfuscations
     * ([at], (at), padded AT, [dot], (dot), [arroba], (arroba), numeric entities)
     * into their real separators and strip zero-width characters.
     */
    private static String deobfuscate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        var result = OBFUSCATED_AT_BRACKET.matcher(text).replaceAll("@");
        result = OBFUSCATED_AT_PARENTHESES.matcher(result).replaceAll("@");
        result = OBFUSCATED_AT_WORD.matcher(result).replaceAll("@");
        result = OBFUSCATED_DOT_BRACKET.matcher(result).replaceAll(".");
        result = OBFUSCATED_DOT_PARENTHESES.matcher(result).replaceAll(".");
        result = OBFUSCATED_ARROBA_BRACKET.matcher(result).replaceAll("@");
        result = OBFUSCATED_ARROBA_PARENTHESES.matcher(result).replaceAll("@");

        return ZERO_WIDTH_CHARS.matcher(result).replaceAll("");
    }

    private static String extractFirstEmail(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        var matcher = EMAIL_PATTERN.matcher(text);
        while (matcher.find()) {
            var email = matcher.group();
            if (isContactEmail(email)) {
                return email;
            }
        }
        return null;
    }

    private static boolean isContactEmail(String email) {
        if (EXCLUDED_EMAIL_PATTERNS.stream().anyMatch(p -> p.matcher(email).find())) {
            return false;
        }
        var domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        return !PLACEHOLDER_DOMAINS.contains(domain);
    }
}
