package com.juanperuzzo.job_hunter.application.service;

import java.util.Map;

/**
 * The profile-derived values that replace the {@code {{PLACEHOLDER}}} tokens of
 * an application email template. {@code values} contains only the placeholder
 * names (without braces) that could actually be resolved from the available
 * data — an absent entry means the field is missing and its line must be dropped.
 *
 * <p>Produced by {@link ProfilePlaceholders#resolve(com.juanperuzzo.job_hunter.domain.model.User, com.juanperuzzo.job_hunter.domain.model.UserProfile)}
 * and shared by the standard-template email resolver and the AI prompt facts block,
 * so both stay derived from exactly the same input.</p>
 */
public record ResolvedPlaceholders(Map<String, String> values) {
}