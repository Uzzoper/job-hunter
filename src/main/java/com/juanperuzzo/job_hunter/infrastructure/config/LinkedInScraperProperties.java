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
