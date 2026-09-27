package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import org.jsoup.Jsoup;

import java.util.ArrayList;
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
 *
 * <p><strong>Preference rule</strong> (email-extractor-recall spec, Phase 2):
 * {@code mailto: pass > regex pass}, then within a pass/scan, hiring-local-part
 * ({@code vagas}, {@code vaga}, {@code rh}, {@code recrutamento}, … see
 * {@link #HIRING_LOCAL_PART_PREFIXES}) beats generic ({@code contato@}, {@code info@},
 * {@code sac@}, personal names); scan order (title before description, DOM/position
 * order) breaks ties. Exclusions and placeholder-domain addresses never become
 * candidates. Only literals survive: an address is returned only if its exact
 * characters occur in the text after de-obfuscation.
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

    /**
     * Local-part prefixes that identify a recruiting/company contact address.
     * A candidate whose normalized local part starts with any of these outranks a
     * generic one (contato@, info@, sac@, personal nome.sobrenome@). Matching is a
     * case-insensitive prefix check on the ASCII local part the regex already matched
     * (accented/space forms like "talent acquisition" can never appear in a matched
     * local part — they are kept for spec fidelity and stay harmless).
     */
    private static final List<String> HIRING_LOCAL_PART_PREFIXES = List.of(
            "vagas", "vaga", "rh", "recrutamento", "selecao", "talentos", "carreiras",
            "jobs", "hiring", "careers", "people", "talent", "talent acquisition");

    private static final Pattern OBFUSCATED_AT_BRACKET = Pattern.compile("\\s*\\[at\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_AT_PARENTHESES = Pattern.compile("\\s*\\(at\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_AT_WORD = Pattern.compile("\\s+at\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT_BRACKET = Pattern.compile("\\s*\\[dot\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT_PARENTHESES = Pattern.compile("\\s*\\(dot\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_ARROBA_BRACKET = Pattern.compile("\\s*\\[arroba\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_ARROBA_PARENTHESES = Pattern.compile("\\s*\\(arroba\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_ARROBA_WORD = Pattern.compile("\\s+arroba\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT_WORD = Pattern.compile("\\s+ponto\\s+", Pattern.CASE_INSENSITIVE);
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
     * Scan {@code a[href^=mailto:]} anchors in DOM order, collect every recipient that
     * passes {@link #isContactEmail(String)}, and return the best-ranked one — an empty
     * candidate list yields {@code null}. Ranking follows the documented preference
     * rule (hiring-local-part beats generic; position breaks ties).
     */
    private static String extractMailto(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        var candidates = new ArrayList<String>();
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
                candidates.add(email);
            }
        }
        return rankWinner(candidates);
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
     * ([at], (at), padded AT, [dot], (dot), [arroba], (arroba), bare-word
     * arroba/ponto, numeric entities) into their real separators and strip
     * zero-width characters.
     * <p>
     * Bare-word forms use conservative spacing ({@code \s+arroba\s+},
     * {@code \s+ponto\s+}) so normal prose (e.g. "ponto de encontro") never
     * decodes without also producing a literal {@code @} — {@link #EMAIL_PATTERN}
     * still requires one, so a bare {@code ponto} alone can never invent an address.
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
        result = OBFUSCATED_ARROBA_WORD.matcher(result).replaceAll("@");
        result = OBFUSCATED_DOT_WORD.matcher(result).replaceAll(".");

        return ZERO_WIDTH_CHARS.matcher(result).replaceAll("");
    }

    /**
     * Collect every email the regex finds in scan order that passes
     * {@link #isContactEmail(String)} and return the best-ranked one — {@code null}
     * when none qualify. Ranking follows the documented preference rule
     * (hiring-local-part beats generic; position breaks ties).
     */
    private static String extractFirstEmail(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        var candidates = new ArrayList<String>();
        var matcher = EMAIL_PATTERN.matcher(text);
        while (matcher.find()) {
            var email = matcher.group();
            if (isContactEmail(email)) {
                candidates.add(email);
            }
        }
        return rankWinner(candidates);
    }

    /**
     * Best candidate by the documented preference rule:
     * {@code mailto: pass > regex pass}, then within a pass/scan, hiring-local-part
     * beats generic; scan order (title before description, DOM/position order) breaks
     * ties. Exclusions and placeholder domains are applied before ranking, so an
     * excluded/placeholder address is never a candidate.
     */
    private static String rankWinner(List<String> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(EmailExtractor::isHiringLocalPart)
                .findFirst()
                .orElse(candidates.get(0));
    }

    private static boolean isHiringLocalPart(String email) {
        var localPart = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        return HIRING_LOCAL_PART_PREFIXES.stream().anyMatch(localPart::startsWith);
    }

    private static boolean isContactEmail(String email) {
        if (EXCLUDED_EMAIL_PATTERNS.stream().anyMatch(p -> p.matcher(email).find())) {
            return false;
        }
        var domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        return !PLACEHOLDER_DOMAINS.contains(domain);
    }
}
