package com.juanperuzzo.job_hunter.application.port.out;

import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Intermediate DTO between provider extraction and domain normalization.
 * All fields nullable except url.
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
