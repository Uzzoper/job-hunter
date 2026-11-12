package com.juanperuzzo.job_hunter.unit.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.FetchRun;
import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;
import com.juanperuzzo.job_hunter.infrastructure.config.InMemoryFetchRunStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InMemoryFetchRunStore tests")
class InMemoryFetchRunStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-12T03:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-12T03:04:00Z");
    private static final String SOURCE = "linkedin";

    private final InMemoryFetchRunStore store = new InMemoryFetchRunStore();

    @Test
    @DisplayName("get should return the idle snapshot when the source never ran")
    void get_whenNeverRun_shouldReturnIdle() {
        var run = store.get(SOURCE);

        assertEquals(FetchRun.State.IDLE, run.state());
        assertNull(run.startedAt());
        assertNull(run.finishedAt());
        assertNull(run.result());
        assertTrue(run.keywordRuns().isEmpty());
        assertNull(run.error());
    }

    @Test
    @DisplayName("tryBegin should transition idle to running with the given startedAt")
    void tryBegin_whenIdle_shouldTransitionToRunning() {
        assertTrue(store.tryBegin(SOURCE, T0));

        var run = store.get(SOURCE);
        assertEquals(FetchRun.State.RUNNING, run.state());
        assertEquals(T0, run.startedAt());
        assertNull(run.finishedAt());
    }

    @Test
    @DisplayName("tryBegin while running should reject and keep the original run untouched")
    void tryBegin_whenAlreadyRunning_shouldRejectAndKeepOriginalRun() {
        assertTrue(store.tryBegin(SOURCE, T0));

        assertFalse(store.tryBegin(SOURCE, T1));

        var run = store.get(SOURCE);
        assertEquals(FetchRun.State.RUNNING, run.state());
        assertEquals(T0, run.startedAt());
    }

    @Test
    @DisplayName("complete should attach the aggregate result and per-keyword outcomes")
    void complete_shouldAttachResultAndKeywordRuns() {
        assertTrue(store.tryBegin(SOURCE, T0));
        var result = new FetchResult(
                4, 4, 1,
                List.of(new ProviderFetchStats(SOURCE, 4, 4, 1, 0, 0, null)));
        var keywordRuns = List.of(
                KeywordRun.ok("dev junior", 4, 9_000),
                KeywordRun.timeout("programador junior", 90_000));

        store.complete(SOURCE, T1, result, keywordRuns);

        var run = store.get(SOURCE);
        assertEquals(FetchRun.State.COMPLETED, run.state());
        assertEquals(T0, run.startedAt());
        assertEquals(T1, run.finishedAt());
        assertEquals(result, run.result());
        assertEquals(keywordRuns, run.keywordRuns());
        assertNull(run.error());
    }

    @Test
    @DisplayName("fail should record a bounded error and keep the startedAt")
    void fail_shouldRecordBoundedError() {
        assertTrue(store.tryBegin(SOURCE, T0));

        store.fail(SOURCE, T1, "x".repeat(500), List.of(KeywordRun.timeout("dev junior", 90_000)));

        var run = store.get(SOURCE);
        assertEquals(FetchRun.State.FAILED, run.state());
        assertEquals(T0, run.startedAt());
        assertEquals(T1, run.finishedAt());
        assertNull(run.result());
        assertEquals(300, run.error().length());
        assertEquals(List.of(KeywordRun.timeout("dev junior", 90_000)), run.keywordRuns());
    }

    @Test
    @DisplayName("tryBegin after a failure should allow a new run (single-flight releases)")
    void tryBegin_afterFailure_shouldAllowNewRun() {
        assertTrue(store.tryBegin(SOURCE, T0));
        store.fail(SOURCE, T1, "failed", List.of());

        assertTrue(store.tryBegin(SOURCE, T1.plusSeconds(1)));

        assertEquals(FetchRun.State.RUNNING, store.get(SOURCE).state());
    }

    @Test
    @DisplayName("concurrent tryBegin calls should produce exactly one winner")
    void tryBegin_whenConcurrent_shouldAllowExactlyOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var ready = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    return store.tryBegin(SOURCE, T0);
                }));
            }
            ready.countDown();
            var winners = 0;
            for (var future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }
}
