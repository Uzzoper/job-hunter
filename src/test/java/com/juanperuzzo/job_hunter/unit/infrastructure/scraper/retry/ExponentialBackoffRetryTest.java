package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.retry;

import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExponentialBackoffRetry tests")
class ExponentialBackoffRetryTest {

    private final ExponentialBackoffRetry retry = new ExponentialBackoffRetry(
            3, Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofMillis(5));

    @Test
    @DisplayName("execute should return result when supplier succeeds on first attempt")
    void execute_whenSucceedsFirstAttempt_shouldReturnResult() {
        var result = retry.execute(() -> "success");
        assertEquals("success", result);
    }

    @Test
    @DisplayName("execute should retry and eventually succeed")
    void execute_whenFailsThenSucceeds_shouldReturnResult() {
        var counter = new int[]{0};
        var result = retry.execute(() -> {
            counter[0]++;
            if (counter[0] < 3) {
                throw new ScraperException("timeout");
            }
            return "success";
        });
        assertEquals("success", result);
        assertEquals(3, counter[0]);
    }

    @Test
    @DisplayName("execute should throw ScraperException after all attempts exhausted")
    void execute_whenAlwaysFails_shouldThrowAfterAllAttempts() {
        assertThrows(ScraperException.class, () ->
                retry.execute(() -> { throw new ScraperException("timeout"); }));
    }

    @Test
    @DisplayName("execute should propagate non-retryable exception immediately")
    void execute_whenNonRetryable_shouldPropagateImmediately() {
        assertThrows(IllegalArgumentException.class, () ->
                retry.execute(() -> { throw new IllegalArgumentException("bad input"); }));
    }
}
