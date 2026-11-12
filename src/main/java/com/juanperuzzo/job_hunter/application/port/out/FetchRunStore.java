package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;

import java.time.Instant;
import java.util.List;

/**
 * Single-flight run registry for asynchronous source fetches (issue #116).
 * In-memory by design — one instance, one owner; restart resets to idle.
 */
public interface FetchRunStore {

    /**
     * Attempts the idle/completed/failed → running transition for the source.
     *
     * @return {@code true} when this call began the run; {@code false} when a
     *         run is already active (single-flight rejection)
     */
    boolean tryBegin(String sourceId, Instant startedAt);

    /** Marks the active run completed and attaches its aggregate + per-keyword outcomes. */
    void complete(String sourceId, Instant finishedAt, FetchResult result, List<KeywordRun> keywordRuns);

    /**
     * Marks the active run failed with an error message (bounded by the
     * implementation), keeping the per-keyword outcomes collected before the
     * failure so partial progress stays caller-visible (issue #116).
     */
    void fail(String sourceId, Instant finishedAt, String error, List<KeywordRun> keywordRuns);
    /** Current run snapshot; {@link FetchRun#idle()} when never run since process start. */
    FetchRun get(String sourceId);
}
