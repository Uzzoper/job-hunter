package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.out.FetchRun;
import com.juanperuzzo.job_hunter.application.port.out.FetchRunStore;
import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory single-flight registry (issue #116, linkedin-async-fetch spec):
 * one entry per source id, transitions atomic via {@link ConcurrentHashMap#compute}.
 * Restart loses state by design — the registry reflects only the current
 * process (spec non-goal: no queue/persistence infra for a single instance).
 */
@Component
public class InMemoryFetchRunStore implements FetchRunStore {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 300;

    private final ConcurrentHashMap<String, FetchRun> runs = new ConcurrentHashMap<>();

    @Override
    public boolean tryBegin(String sourceId, Instant startedAt) {
        var next = FetchRun.running(startedAt);
        var stored = runs.compute(sourceId, (id, current) ->
                current != null && current.state() == FetchRun.State.RUNNING ? current : next);
        return stored == next;
    }

    @Override
    public void complete(String sourceId, Instant finishedAt, FetchResult result, List<KeywordRun> keywordRuns) {
        runs.compute(sourceId, (id, current) -> new FetchRun(
                FetchRun.State.COMPLETED,
                current == null ? null : current.startedAt(),
                finishedAt,
                result,
                List.copyOf(keywordRuns),
                null));
    }

    @Override
    public void fail(String sourceId, Instant finishedAt, String error, List<KeywordRun> keywordRuns) {
        runs.compute(sourceId, (id, current) -> new FetchRun(
                FetchRun.State.FAILED,
                current == null ? null : current.startedAt(),
                finishedAt,
                null,
                List.copyOf(keywordRuns),
                bound(error)));
    }

    @Override
    public FetchRun get(String sourceId) {
        return runs.getOrDefault(sourceId, FetchRun.idle());
    }

    private static String bound(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
