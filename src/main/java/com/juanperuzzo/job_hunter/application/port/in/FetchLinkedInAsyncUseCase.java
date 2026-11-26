package com.juanperuzzo.job_hunter.application.port.in;

import java.time.Instant;

/**
 * Starts an asynchronous LinkedIn fetch (issue #116, linkedin-async-fetch
 * spec): the HTTP caller gets an answer before the fetch finishes and polls
 * {@link FetchRunStatusUseCase} for progress. Every other source keeps the
 * synchronous contract.
 */
public interface FetchLinkedInAsyncUseCase {

    /**
     * Single-flight start: accepts the run and schedules fetch+save on the async
     * executor, or rejects it while another run is active.
     *
     * @return the outcome plus the run's {@code startedAt} (the active run's
     *         start time when the call is rejected)
     */
    StartResult start();

    enum StartOutcome { STARTED, ALREADY_RUNNING }

    /**
     * @param outcome   whether this call began a new run
     * @param startedAt start time of the run (new or already active)
     */
    record StartResult(StartOutcome outcome, Instant startedAt) {}
}
