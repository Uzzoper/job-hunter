package com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter implements RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double defaultPermitsPerSecond;
    private final int defaultBurst;

    public record RateLimitConfig(double permitsPerSecond, int burst) {}

    public TokenBucketRateLimiter(double defaultPermitsPerSecond, int defaultBurst,
                                   Map<String, RateLimitConfig> overrides) {
        this.defaultPermitsPerSecond = defaultPermitsPerSecond;
        this.defaultBurst = defaultBurst;
        if (overrides != null) {
            overrides.forEach((providerId, config) ->
                    buckets.put(providerId, new Bucket(config.burst(), config.permitsPerSecond())));
        }
    }

    @Override
    public boolean tryAcquire(String providerId) {
        var bucket = getOrCreateBucket(providerId);
        return bucket.tryAcquire();
    }

    @Override
    public void acquire(String providerId) {
        var bucket = getOrCreateBucket(providerId);
        bucket.acquire();
    }

    private Bucket getOrCreateBucket(String providerId) {
        return buckets.computeIfAbsent(providerId, id ->
                new Bucket(defaultBurst, defaultPermitsPerSecond));
    }

    private static class Bucket {
        private final ReentrantLock lock = new ReentrantLock();
        private final long capacity;
        private final double refillPerSecond;
        private long availableTokens;
        private long lastRefillNanos;

        Bucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.availableTokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        boolean tryAcquire() {
            refill();
            lock.lock();
            try {
                if (availableTokens > 0) {
                    availableTokens--;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        void acquire() {
            while (!tryAcquire()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void refill() {
            lock.lock();
            try {
                var now = System.nanoTime();
                var elapsed = now - lastRefillNanos;
                var tokensToAdd = (long) (elapsed / 1_000_000_000.0 * refillPerSecond);
                if (tokensToAdd > 0) {
                    availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
                    lastRefillNanos = now;
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
