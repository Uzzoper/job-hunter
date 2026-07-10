package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Centralized normalizer that transforms a {@link RawJob} into a domain {@link Job}.
 * <p>
 * Pipeline: clean → normalize → parseDate → matchKeywords → isExcluded →
 * matchLocation → filterByAge → decode → mapToJob.
 */
public class JobNormalizer {

    private static final Logger log = LoggerFactory.getLogger(JobNormalizer.class);

    private static final List<String> REMOTE_TERMS = List.of(
            "home office", "remoto", "todo brasil", "teletrabalho", "remote");

    private static final Pattern CONSECUTIVE_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern WORD_BOUNDARY_PATTERN =
            Pattern.compile("(?<![\\p{L}\\p{N}_])", Pattern.UNICODE_CHARACTER_CLASS);

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

        return new Job(null, cleanText(raw.title()), company, raw.url(), description, postedDate);
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
                .replaceAll("&[a-zA-Z]+;", "?");
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
