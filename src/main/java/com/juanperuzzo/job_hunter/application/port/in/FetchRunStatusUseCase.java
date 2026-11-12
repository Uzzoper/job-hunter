package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.application.port.out.FetchRun;

/**
 * Read side of the async fetch contract (issue #116): exposes the current run
 * state so callers poll instead of blocking. LinkedIn-scoped — the only source
 * with an asynchronous HTTP contract (docs/specs/linkedin-async-fetch.md).
 */
public interface FetchRunStatusUseCase {

    /** Snapshot of the current (or last) run; {@code idle} before the first run since process start. */
    FetchRun status();
}
