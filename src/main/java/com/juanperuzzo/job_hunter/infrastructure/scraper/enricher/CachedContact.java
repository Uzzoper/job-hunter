package com.juanperuzzo.job_hunter.infrastructure.scraper.enricher;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Cached company-contact lookup result (email-enrichment spec, Scenario 15).
 *
 * @param email     contact email found on the company site, or {@code null} for a negative cache entry
 * @param fetchedAt instant the result was fetched (used for the TTL check)
 */
public record CachedContact(String email, Instant fetchedAt) {
    public CachedContact {
        requireNonNull(fetchedAt, "fetchedAt must not be null");
    }
}