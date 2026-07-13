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
    long detailFetchDelayMillis
) {}
