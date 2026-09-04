package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.ratelimit;

import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenBucketRateLimiter tests")
class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("tryAcquire should allow burst capacity then block")
    void tryAcquire_shouldAllowBurstThenBlock() {
        var limiter = new TokenBucketRateLimiter(10, 5, Map.of());
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("gupy"), "Attempt " + (i + 1) + " should succeed");
        }
        assertFalse(limiter.tryAcquire("gupy"), "Should be blocked after burst");
    }

    @Test
    @DisplayName("per-provider configuration should be respected")
    void tryAcquire_withPerProviderConfig_shouldRespectLimits() {
        var overrides = Map.of(
                "gupy", new TokenBucketRateLimiter.RateLimitConfig(100, 10),
                "infojobs", new TokenBucketRateLimiter.RateLimitConfig(1, 1)
        );
        var limiter = new TokenBucketRateLimiter(10, 5, overrides);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("gupy"), "Gupy attempt " + (i + 1));
        }
        assertTrue(limiter.tryAcquire("infojobs"), "InfoJobs first should succeed");
        assertFalse(limiter.tryAcquire("infojobs"), "InfoJobs second should fail");
    }

    @Test
    @DisplayName("concurrent access should not deadlock")
    void concurrentAccess_shouldNotDeadlock() throws InterruptedException {
        var limiter = new TokenBucketRateLimiter(0, 10, Map.of());
        var successCount = new AtomicInteger(0);
        var threads = new Thread[5];

        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 5; j++) {
                    if (limiter.tryAcquire("gupy")) {
                        successCount.incrementAndGet();
                    }
                }
            });
            threads[i].start();
        }

        for (var t : threads) {
            t.join();
        }

        assertTrue(successCount.get() > 0, "At least some acquires should succeed");
        assertTrue(successCount.get() <= 10, "Should not exceed burst capacity");
    }
}
