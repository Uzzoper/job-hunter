package com.juanperuzzo.job_hunter.domain;

import java.util.List;
import java.util.Locale;

/**
 * Shared constants for job-portal domains that are not corporate sites. Crawling these
 * wastes time and blocks fetch/enrichment, so both the company-site enricher
 * (infrastructure) and the enrichment service (application) skip them. Kept in the
 * domain layer so both sides can reference the same list without the application
 * depending on infrastructure.
 */
public final class PortalDomains {

    public static final List<String> SUFFIXES = List.of(
            "gupy.io", "gupy.com.br", "infojobs.com.br");

    private PortalDomains() {
    }

    /** True when the given lowercase host ends with a known job-portal suffix. */
    public static boolean isPortal(String host) {
        return SUFFIXES.stream().anyMatch(host::endsWith);
    }
}
