package com.juanperuzzo.job_hunter.web.dto;

import java.time.Instant;

/**
 * Body of {@code POST /api/jobs/fetch/linkedin} for the async contract
 * (issue #116): {@code 202 status=started} on acceptance,
 * {@code 409 status=running} on single-flight rejection.
 */
public record FetchStartedResponse(String status, Instant startedAt) {

    public static FetchStartedResponse started(Instant startedAt) {
        return new FetchStartedResponse("started", startedAt);
    }

    public static FetchStartedResponse running(Instant startedAt) {
        return new FetchStartedResponse("running", startedAt);
    }
}
