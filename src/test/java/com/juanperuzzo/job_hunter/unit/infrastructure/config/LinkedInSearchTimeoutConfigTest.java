package com.juanperuzzo.job_hunter.unit.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the LinkedIn search-timeout invariant diagnosed in issue #120: the
 * live paginated search (exact backend query, single keyword) measured
 * <b>145.67s</b> with HTTP 200 and 120 jobs on 2026-11-12 — a 90s budget
 * starved every keyword into {@code timeout} and returned 0 jobs/night. The
 * configured {@code search-timeout-seconds} must always cover that measured
 * floor with margin; the value itself may grow (240, 300…), only the floor
 * is invariant. Plain JUnit on purpose: the invariant lives in
 * {@code application.yaml}, not in code (the record has no defaults).
 */
@DisplayName("LinkedIn search timeout config should cover the measured search floor")
class LinkedInSearchTimeoutConfigTest {

    /** Measured live search time + margin (145.67s rounded up). */
    private static final int MEASURED_FLOOR_SECONDS = 150;

    private static final Pattern TIMEOUT_LINE =
            Pattern.compile("search-timeout-seconds:\\s*(\\d+)");

    @Test
    @DisplayName("search-timeout-seconds should stay above the measured 145.67s live search")
    void searchTimeout_whenConfigured_shouldCoverMeasuredSearchTime() throws IOException {
        var yaml = Files.readString(Path.of("src", "main", "resources", "application.yaml"));
        var matcher = TIMEOUT_LINE.matcher(yaml);

        assertTrue(matcher.find(), "application.yaml must configure scraper.linkedin.search-timeout-seconds");
        int configuredSeconds = Integer.parseInt(matcher.group(1));
        var message = ("search-timeout-seconds=%ds is below the measured live search floor (~145.67s, issue #120)"
                + " — keywords would starve into timeout again").formatted(configuredSeconds);

        assertTrue(configuredSeconds >= MEASURED_FLOOR_SECONDS, message);
    }
}
