package com.juanperuzzo.job_hunter.infrastructure.scraper.retry;

import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class ExponentialBackoffRetry implements RetryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExponentialBackoffRetry.class);

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final long maxJitterMillis;
    private final Map<Integer, Integer> retryStatuses;

    public ExponentialBackoffRetry(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration maxJitter) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelay.toMillis();
        this.maxDelayMillis = maxDelay.toMillis();
        this.maxJitterMillis = maxJitter.toMillis();
        this.retryStatuses = Map.of(429, 3);
    }

    public Map<Integer, Integer> getRetryStatuses() {
        return retryStatuses;
    }

    @Override
    public <T> T execute(Supplier<T> supplier) {
        Throwable lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (Exception e) {
                lastException = e;

                if (!isRetryable(e)) {
                    throw e;
                }

                if (attempt == maxAttempts) {
                    throw new ScraperException(
                            "All " + maxAttempts + " retry attempts exhausted", e);
                }

                long delay = Math.min(baseDelayMillis * (1L << (attempt - 1)), maxDelayMillis);
                long jitter = ThreadLocalRandom.current().nextLong(0, maxJitterMillis + 1);
                long totalDelay = delay + jitter;

                log.warn("Retry attempt {}/{} after {}ms (base={}ms, jitter={}ms)",
                        attempt, maxAttempts, totalDelay, delay, jitter);

                try {
                    Thread.sleep(totalDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ScraperException("Retry interrupted", ie);
                }
            }
        }

        throw new ScraperException("All " + maxAttempts + " retry attempts exhausted", lastException);
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof SocketTimeoutException) return true;
        if (hasRetryableToken(e)) return true;
        return hasTimeoutCause(e);
    }

    /**
     * Message-token check applies to ANY exception type so non-scraper clients
     * (e.g. {@code AiException} wrapping HTTP 429/5xx bodies) are retried too.
     */
    private static boolean hasRetryableToken(Throwable e) {
        var message = e.getMessage();
        if (message == null) {
            return false;
        }
        var lower = message.toLowerCase();
        return lower.contains("timeout") || lower.contains("429") || lower.contains("5xx")
                || lower.contains("503") || lower.contains("502") || lower.contains("500");
    }

    private static boolean hasTimeoutCause(Throwable e) {
        var cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }
}
