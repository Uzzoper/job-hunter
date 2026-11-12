package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;

import java.time.Instant;
import java.util.List;

/**
 * Read model of one asynchronous source-fetch run (issue #116,
 * linkedin-async-fetch spec). Lives in memory only: a process restart returns
 * the run to {@link State#IDLE} (spec non-goal: no queue/persistence infra for
 * a single-instance, single-owner deployment).
 *
 * @param state       idle | running | completed | failed
 * @param startedAt   when the run began (null while idle)
 * @param finishedAt  when the run completed or failed (null otherwise)
 * @param result      aggregate fetch statistics (null unless completed)
 * @param keywordRuns per-keyword outcomes, attached at completion (empty while running)
 * @param error       failure message bounded to 300 chars (null unless failed)
 */
public record FetchRun(
        State state,
        Instant startedAt,
        Instant finishedAt,
        FetchResult result,
        List<KeywordRun> keywordRuns,
        String error) {

    public enum State { IDLE, RUNNING, COMPLETED, FAILED }

    /** Snapshot shown before the first run since process start. */
    public static FetchRun idle() {
        return new FetchRun(State.IDLE, null, null, null, List.of(), null);
    }

    /** Snapshot shown between {@code tryBegin} and completion/failure. */
    public static FetchRun running(Instant startedAt) {
        return new FetchRun(State.RUNNING, startedAt, null, null, List.of(), null);
    }
}
