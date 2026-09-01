package com.juanperuzzo.job_hunter.application.port.out;

import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Intermediate DTO between provider extraction and domain normalization.
 * All fields nullable except url.
 * <p>
 * Metadata keys (see email-enrichment spec):
 * <ul>
 *   <li>{@code companyWebsite} — absolute company site URL (nullable), copied to {@code Job.companyWebsite}</li>
 *   <li>{@code detailFailed} — {@code "true"} when a provider fell back to its card snippet after a detail fetch failure</li>
 * </ul>
 */
public record RawJob(
        String title,
        String company,
        String url,
        String description,
        String rawDate,
        String location,
        String workModel,
        String source,
        Map<String, String> metadata
) {
    public RawJob {
        requireNonNull(url, "url must not be null");
        requireNonNull(source, "source must not be null");
        if (metadata == null) {
            metadata = new HashMap<>();
        }
    }
}
