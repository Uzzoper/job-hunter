package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;

/**
 * Resolves search keywords for a user at request time.
 * <p>
 * Implementation returns the stored keywords when present and non-empty,
 * otherwise an empty list. When the list is empty, each call site falls back to
 * its own per-provider yaml default. Single-owner (self-hosted) scope.
 */
public interface KeywordResolver {
    List<String> resolve();
}
