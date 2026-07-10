package com.juanperuzzo.job_hunter.infrastructure.scraper.retry;

import java.util.function.Supplier;

@FunctionalInterface
public interface RetryStrategy {
    <T> T execute(Supplier<T> supplier);
}
