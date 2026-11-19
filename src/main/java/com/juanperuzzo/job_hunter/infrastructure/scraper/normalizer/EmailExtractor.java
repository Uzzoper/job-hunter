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
 * <p><strong>Channel vetoes</strong> (async-company-enrichment Phase 3, owner decision
 * 2026-11-19): local parts starting with {@code dpo}, {@code privacidade}, {@code juridico},
 * {@code comercial} or {@code vendas} are hard-excluded in both passes — they are LGPD or
 * sales channels, not application contacts. Generic addresses ({@code contato@},
 * {@code almap@}) and hiring addresses ({@code vagas@}, {@code rh@}) stay allowed.
 *
 * <p><strong>Site scoping</strong>: {@link #extractFromHtml(String, String)} additionally
 * requires the address to belong to the crawled site (equal, parent or subdomain at a
 * label boundary). The enricher uses it so a theme placeholder or a UI artifact can never
 * be attached to a job; the normalizer path ({@link #extract(String, String)}) stays
 * unscoped.
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
            "yourdomain.com", "seuemail.com", "mysite.com");

    /**
     * Local-part prefixes that identify a channel which must never receive an
     * application: data-protection (LGPD), legal, sales and vendor contact
     * addresses. Checked as a case-insensitive prefix on the ASCII local part,
     * before ranking — a vetoed address is never a candidate.
     */
    private static final List<String> VETOED_LOCAL_PART_PREFIXES = List.of(
            "dpo", "privacidade", "juridico", "comercial", "vendas");

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
     * (noreply/donotreply/no-reply/apply + placeholders + channel vetoes) still apply.
     * This path is unscoped: it has no crawled host to compare against.
     */
    public static String extract(String title, String description) {
        var mailto = extractMailto(title, null);
        if (mailto == null && description != null) {
            mailto = extractMailto(description, null);
        }
        if (mailto != null) {
            return mailto;
        }

        var candidate = extractFirstEmail(deobfuscate(toPlainText(title)), null);
        if (candidate == null && description != null) {
            candidate = extractFirstEmail(deobfuscate(toPlainText(description)), null);
        }
        return candidate;
    }

    /**
     * Extract a contact email from a single raw HTML document, using the same priority
     * as {@link #extract(String, String)} but over one payload. Returns {@code null} if none.
     */
    public static String extractFromHtml(String html) {
        return extractFromHtml(html, null);
    }

    /**
     * Extract a contact email from a single raw HTML document, accepting only addresses
     * that belong to {@code siteHost} — the host the document was crawled from.
     *
     * <p>A candidate belongs to the site when its domain equals the host or is a
     * label-boundary parent/subdomain of it: {@code contato@grupolevel.com.br} on
     * {@code www.grupolevel.com.br} passes; {@code contact@mysite.com} and
     * {@code grupolevel.com.brCopiar} (a UI label/button swallowed into the address)
     * do not. {@code siteHost == null} or blank disables the scope.
     *
     * @param html     raw HTML document
     * @param siteHost host of the crawled page (e.g. {@code www.empresa.com.br})
     * @return the best-ranked same-site address, or {@code null}
     */
    public static String extractFromHtml(String html, String siteHost) {
        if (html == null || html.isBlank()) {
            return null;
        }
        var mailto = extractMailto(html, siteHost);
        if (mailto != null) {
            return mailto;
        }
        return extractFirstEmail(deobfuscate(toPlainText(html)), siteHost);
    }

    /**
     * Scan {@code a[href^=mailto:]} anchors in DOM order, collect every recipient that
     * passes {@link #isContactEmail(String, String)}, and return the best-ranked one — an
     * empty candidate list yields {@code null}. Ranking follows the documented preference
     * rule (hiring-local-part beats generic; position breaks ties).
     */
    private static String extractMailto(String html, String siteHost) {
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

            if (isContactEmail(email, siteHost)) {
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
     * {@link #isContactEmail(String, String)} and return the best-ranked one — {@code null}
     * when none qualify. Ranking follows the documented preference rule
     * (hiring-local-part beats generic; position breaks ties).
     */
    private static String extractFirstEmail(String text, String siteHost) {
        if (text == null || text.isBlank()) {
            return null;
        }

        var candidates = new ArrayList<String>();
        var matcher = EMAIL_PATTERN.matcher(text);
        while (matcher.find()) {
            var email = matcher.group();
            if (isContactEmail(email, siteHost)) {
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

    private static boolean isContactEmail(String email, String siteHost) {
        var at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return false;
        }
        var localPart = email.substring(0, at).toLowerCase(Locale.ROOT);
        if (VETOED_LOCAL_PART_PREFIXES.stream().anyMatch(localPart::startsWith)) {
            return false;
        }
        if (EXCLUDED_EMAIL_PATTERNS.stream().anyMatch(p -> p.matcher(email).find())) {
            return false;
        }
        var domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_DOMAINS.contains(domain)) {
            return false;
        }
        return belongsToSite(domain, siteHost);
    }

    /**
     * True when an address domain and the crawled host belong to the same site:
     * equal, or one is a label-boundary parent/subdomain of the other. A blank
     * host disables the scope (normalizer path).
     *
     * <p>{@code emailDomain} is expected lowercased; the host is normalized here.
     * The rule is deliberately conservative: two sibling subdomains do not match
     * each other — only equal, ancestor or descendant relations pass.
     */
    private static boolean belongsToSite(String emailDomain, String siteHost) {
        if (siteHost == null) {
            return true;
        }
        var host = siteHost.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return true;
        }
        return emailDomain.equals(host)
                || emailDomain.endsWith("." + host)
                || host.endsWith("." + emailDomain);
    }
}
