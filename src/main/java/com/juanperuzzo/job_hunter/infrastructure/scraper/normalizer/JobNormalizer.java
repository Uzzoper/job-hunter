package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralized normalizer that transforms a {@link RawJob} into a domain {@link Job}.
 * <p>
 * Pipeline: clean → normalize → parseDate → matchKeywords → isExcluded →
 * matchLocation → filterByAge → decode → extract (mailto → deobfuscate → regex) → mapToJob.
 */
public class JobNormalizer implements NormalizerPort {

    private static final Logger log = LoggerFactory.getLogger(JobNormalizer.class);

    private static final List<String> REMOTE_TERMS = List.of(
            "home office", "remoto", "todo brasil", "teletrabalho", "remote");

    private static final Pattern CONSECUTIVE_WHITESPACE = Pattern.compile("\\s+");

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

    private final DateParser dateParser;
    private final List<String> keywords;
    private final List<Pattern> excludePatterns;
    private final List<String> locations;
    private final int maxAgeDays;
    private final Clock clock;

    public JobNormalizer(
            DateParser dateParser,
            List<String> keywords,
            List<Pattern> excludePatterns,
            List<String> locations,
            int maxAgeDays,
            Clock clock) {
        this.dateParser = dateParser;
        this.keywords = keywords != null ? keywords : List.of();
        this.excludePatterns = excludePatterns != null ? excludePatterns : List.of();
        this.locations = locations != null ? locations : List.of();
        this.maxAgeDays = maxAgeDays;
        this.clock = clock;
    }

    /**
     * Normalize a single RawJob into a Job. Returns null if the job should be skipped.
     */
    public Job normalize(RawJob raw) {
        if (raw.title() == null || raw.title().isBlank()) {
            log.debug("Skipping raw job {}: blank title", raw.url());
            return null;
        }

        var normalizedTitle = normalizeText(raw.title());
        var normalizedDescription = raw.description() != null ? normalizeText(raw.description()) : "";

        if (!matchesKeywords(normalizedTitle, normalizedDescription)) {
            log.debug("Skipping {}: no keywords match in title/description", raw.url());
            return null;
        }

        if (isExcluded(normalizedTitle)) {
            log.debug("Skipping {}: excluded by pattern", raw.url());
            return null;
        }

        if (!matchesLocation(normalizedTitle, normalizedDescription, raw.location(), raw.workModel())) {
            log.debug("Skipping {}: location does not match", raw.url());
            return null;
        }

        var postedAt = dateParser.parse(raw.rawDate());
        if (postedAt.isEmpty()) {
            log.debug("Skipping {}: could not parse date '{}'", raw.url(), raw.rawDate());
            return null;
        }

        var postedDate = postedAt.get();
        if (isTooOld(postedDate)) {
            log.debug("Skipping {}: too old ({} days)", raw.url(), maxAgeDays);
            return null;
        }

        var company = raw.company() != null ? cleanText(raw.company()) : "";
        var description = raw.description() != null ? decodeEntities(raw.description()) : "";
        var contactEmail = extractContactEmail(raw.title(), raw.description());
        var companyWebsite = normalizeCompanyWebsite(raw.metadata().get("companyWebsite"));

        return new Job(null, cleanText(raw.title()), company, raw.url(), description, postedDate, raw.source(),
                contactEmail, companyWebsite);
    }

    public List<Job> normalizeAll(List<RawJob> rawJobs) {
        return rawJobs.stream()
                .map(this::normalize)
                .filter(job -> job != null)
                .toList();
    }

    public static String cleanText(String text) {
        if (text == null || text.isBlank()) return "";
        return CONSECUTIVE_WHITESPACE.matcher(text.strip()).replaceAll(" ");
    }

    /**
     * Normalize text for matching: NFD decompose, remove diacritics, lowercase,
     * replace "jr" → "junior", "sr" → "senior", "pl" → "pleno".
     */
    public static String normalizeText(String text) {
        var cleaned = cleanText(text);
        if (cleaned.isEmpty()) return cleaned;

        var decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        var noDiacritics = decomposed
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();

        // Expand common abbreviations for better keyword matching
        return noDiacritics
                .replaceAll("(?<![\\p{L}\\p{N}_])jr(?![\\p{L}\\p{N}_])", "junior")
                .replaceAll("(?<![\\p{L}\\p{N}_])sr(?![\\p{L}\\p{N}_])", "senior")
                .replaceAll("(?<![\\p{L}\\p{N}_])pl(?![\\p{L}\\p{N}_])", "pleno");
    }

    /**
     * Decode HTML entities in text.
     */
    public static String decodeEntities(String text) {
        if (text == null || text.isBlank()) return "";
        // Basic entity decoding for common HTML entities
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&#64;", "@")
                .replace("&#x40;", "@")
                .replaceAll("&[a-zA-Z]+;", "?");
    }

    /**
     * Extract a contact email from title or description, following the P0 priority:
     * <ol>
     *   <li>{@code mailto:} links first (DOM order, title before description)</li>
     *   <li>regex over the decoded + parsed text, on a de-obfuscated copy (title before description)</li>
     * </ol>
     * Returns null if no valid email is found. Existing filters
     * (noreply/donotreply/no-reply/apply + placeholder domains) still apply.
     */
    static String extractContactEmail(String title, String description) {
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
     * Scan {@code a[href^=mailto:]} anchors in DOM order and return the first
     * mailto recipient that passes {@link #isContactEmail(String)}.
     */
    private static String extractMailto(String html) {
        if (html == null || html.isBlank()) return null;

        var doc = Jsoup.parse(html);
        for (var anchor : doc.select("a[href^=mailto:]")) {
            var href = anchor.attr("href");
            if (href == null || href.isBlank()) continue;

            var email = href.substring("mailto:".length()).trim();
            if (email.isEmpty()) continue;

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
        if (html == null || html.isBlank()) return "";
        return Jsoup.parse(decodeEntities(html)).text();
    }

    /**
     * Normalize a text copy for extraction: turn common obfuscations
     * ([at], (at), padded AT, [dot], (dot), [arroba], (arroba), numeric entities)
     * into their real separators and strip zero-width characters.
     */
    private static String deobfuscate(String text) {
        if (text == null || text.isBlank()) return "";

        var result = OBFUSCATED_AT_BRACKET.matcher(text).replaceAll("@");
        result = OBFUSCATED_AT_PARENTHESES.matcher(result).replaceAll("@");
        result = OBFUSCATED_AT_WORD.matcher(result).replaceAll("@");
        result = OBFUSCATED_DOT_BRACKET.matcher(result).replaceAll(".");
        result = OBFUSCATED_DOT_PARENTHESES.matcher(result).replaceAll(".");
        result = OBFUSCATED_ARROBA_BRACKET.matcher(result).replaceAll("@");
        result = OBFUSCATED_ARROBA_PARENTHESES.matcher(result).replaceAll("@");

        return ZERO_WIDTH_CHARS.matcher(result).replaceAll("");
    }

    /**
     * Copy the provider-supplied company website into the domain model.
     * Returns null for absent or blank metadata.
     */
    private static String normalizeCompanyWebsite(String website) {
        if (website == null) return null;
        var trimmed = website.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String extractFirstEmail(String text) {
        if (text == null || text.isBlank()) return null;

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
        var domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        return !PLACEHOLDER_DOMAINS.contains(domain);
    }

    private boolean matchesKeywords(String normalizedTitle, String normalizedDescription) {
        if (keywords.isEmpty()) return true;

        return keywords.stream().anyMatch(keyword -> {
            var normalizedKeyword = normalizeText(keyword);
            var tokens = normalizedKeyword.split("[^a-z0-9]+");
            var allTokensMatch = Arrays.stream(tokens)
                    .filter(token -> token.length() > 2)
                    .allMatch(token ->
                            normalizedTitle.contains(token) || normalizedDescription.contains(token));
            // If no tokens are > 2 chars, fall back to simple substring match
            if (Arrays.stream(tokens).noneMatch(token -> token.length() > 2)) {
                return normalizedTitle.contains(normalizedKeyword);
            }
            return allTokensMatch;
        });
    }

    private boolean isExcluded(String normalizedTitle) {
        if (excludePatterns.isEmpty()) return false;
        return excludePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(normalizedTitle).find());
    }

    private boolean matchesLocation(
            String normalizedTitle,
            String normalizedDescription,
            String location,
            String workModel) {
        if (locations.isEmpty()) return true;

        var searchableText = normalizeText(
                String.join(" ",
                        normalizedTitle,
                        normalizedDescription,
                        location != null ? location : "",
                        workModel != null ? workModel : "")
        );

        // Remote terms always match
        for (var term : REMOTE_TERMS) {
            if (searchableText.contains(normalizeText(term))) {
                return true;
            }
        }

        // Check against configured locations
        return locations.stream().anyMatch(loc ->
                searchableText.contains(normalizeText(loc)));
    }

    /**
     * Check if the job is older than maxAgeDays.
     */
    private boolean isTooOld(LocalDate postedAt) {
        return postedAt.isBefore(LocalDate.now(clock).minusDays(maxAgeDays));
    }
}
