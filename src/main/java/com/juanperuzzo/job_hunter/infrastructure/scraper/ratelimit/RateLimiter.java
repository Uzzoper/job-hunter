package com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit;

public interface RateLimiter {
    boolean tryAcquire(String providerId);
    void acquire(String providerId);
}
