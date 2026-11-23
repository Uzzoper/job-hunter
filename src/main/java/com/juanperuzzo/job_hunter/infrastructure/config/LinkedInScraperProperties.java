package com.juanperuzzo.job_hunter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "scraper.linkedin")
public record LinkedInScraperProperties(
    boolean enabled,
    String mode,
    String serviceUrl,
    int timeoutSeconds,
    int connectTimeoutSeconds,
    int maxJobs,
    /**
     * Per-keyword budget for the search loop
     * ({@code scraper.linkedin.per-keyword-limit}, shipped as 25 in
     * {@code application.yaml}; {@code 0} or absent keeps the derived
     * {@code ceil(max-jobs / keywords.size())} quota).
     *
     * <p>Decoupled from {@code max-jobs} on purpose: deriving the budget from
     * {@code ceil(max-jobs / keywords.size())} made every added keyword shrink
     * every other term's coverage (10 keywords x 60 total = 6 each, while the
     * guest search returns ~120 cards per productive term). {@code 0} keeps the
     * derived quota for backwards compatibility.
     */
    int perKeywordLimit,
    String baseUrl,
    List<String> keywords,
    String locations,
    List<String> geoIds,
    List<String> seniority,
    List<String> workType,
    String timeRange,
    int maxPages,
    long detailFetchDelayMillis,
    String baseJobUrl,
    int searchTimeoutSeconds,
    /**
     * jsoup fallback knobs ({@code scraper.linkedin.jsoup.*}). Nested so the
     * fallback path has its own pagination knob independent of the service mode.
     */
    Jsoup jsoup
) {

    /** Knobs for the jsoup fallback strategy ({@code scraper.linkedin.jsoup.max-pages}). */
    public record Jsoup(
        /** Pagination cap per keyword for the jsoup fallback (LinkedInProvider). */
        int maxPages
    ) {}
}
