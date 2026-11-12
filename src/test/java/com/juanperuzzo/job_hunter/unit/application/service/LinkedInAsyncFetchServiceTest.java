package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchLinkedInAsyncUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.FetchRun;
import com.juanperuzzo.job_hunter.application.port.out.FetchRunStore;
import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;
import com.juanperuzzo.job_hunter.application.service.LinkedInAsyncFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LinkedInAsyncFetchService tests")
class LinkedInAsyncFetchServiceTest {

    private static final long USER_ID = 1L;

    private FetchSourceJobsUseCase fetchSourceJobs;
    private FetchRunStore runStore;
    private List<Runnable> submitted;
    private LinkedInAsyncFetchService service;

    @BeforeEach
    void setUp() {
        fetchSourceJobs = mock(FetchSourceJobsUseCase.class);
        runStore = mock(FetchRunStore.class);
        submitted = new ArrayList<>();
        Executor capturingExecutor = submitted::add;
        service = new LinkedInAsyncFetchService(fetchSourceJobs, runStore, capturingExecutor);
    }

    @Test
    @DisplayName("start should begin the run and schedule the fetch on the executor without executing it inline")
    void start_whenIdle_shouldBeginRunAndScheduleFetch() {
        when(runStore.tryBegin(eq(LinkedInAsyncFetchService.SOURCE_ID), any(Instant.class))).thenReturn(true);

        var result = service.start(USER_ID);

        assertEquals(FetchLinkedInAsyncUseCase.StartOutcome.STARTED, result.outcome());
        assertNotNull(result.startedAt());
        assertEquals(1, submitted.size());
        verify(runStore).tryBegin(eq(LinkedInAsyncFetchService.SOURCE_ID), any(Instant.class));
        verifyNoInteractions(fetchSourceJobs);
    }

    @Test
    @DisplayName("executing the scheduled work should fetch with a keyword listener and complete the run with collected runs")
    void run_whenFetchSucceeds_shouldCompleteRunWithCollectedKeywordRuns() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(true);
        var result = new FetchResult(
                4, 4, 1,
                List.of(new ProviderFetchStats("linkedin", 4, 4, 1, 0, 0, null)));
        var emitted = KeywordRun.ok("dev junior", 4, 9_000);
        when(fetchSourceJobs.fetchAndSave(eq(USER_ID), eq(LinkedInAsyncFetchService.SOURCE_ID), any()))
                .thenAnswer(invocation -> {
                    Consumer<KeywordRun> keywordListener = invocation.getArgument(2);
                    keywordListener.accept(emitted);
                    return result;
                });

        service.start(USER_ID);
        submitted.get(0).run();

        verify(runStore).complete(
                eq(LinkedInAsyncFetchService.SOURCE_ID), any(Instant.class), eq(result), eq(List.of(emitted)));
        verify(runStore, never()).fail(anyString(), any(Instant.class), anyString(), anyList());
    }

    @Test
    @DisplayName("second start while a run is active should be rejected without scheduling work")
    void start_whenAlreadyRunning_shouldRejectWithoutScheduling() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(false);
        var activeStartedAt = Instant.parse("2026-11-12T02:55:00Z");
        when(runStore.get(LinkedInAsyncFetchService.SOURCE_ID)).thenReturn(FetchRun.running(activeStartedAt));

        var result = service.start(USER_ID);

        assertEquals(FetchLinkedInAsyncUseCase.StartOutcome.ALREADY_RUNNING, result.outcome());
        assertEquals(activeStartedAt, result.startedAt());
        assertTrue(submitted.isEmpty());
        verifyNoInteractions(fetchSourceJobs);
    }

    @Test
    @DisplayName("executing the scheduled work should mark the run failed when the provider itself reports an error")
    void run_whenProviderReportsError_shouldMarkRunFailedWithKeywordRuns() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(true);
        var emitted = KeywordRun.ok("dev junior", 2, 100);
        var result = new FetchResult(
                0, 0, 0,
                List.of(new ProviderFetchStats("linkedin", 0, 0, 0, 0, 0, "Read timed out")));
        when(fetchSourceJobs.fetchAndSave(eq(USER_ID), eq(LinkedInAsyncFetchService.SOURCE_ID), any()))
                .thenAnswer(invocation -> {
                    Consumer<KeywordRun> keywordListener = invocation.getArgument(2);
                    keywordListener.accept(emitted);
                    return result;
                });

        service.start(USER_ID);
        submitted.get(0).run();

        verify(runStore).fail(
                eq(LinkedInAsyncFetchService.SOURCE_ID), any(Instant.class), eq("Read timed out"), eq(List.of(emitted)));
        verify(runStore, never()).complete(anyString(), any(Instant.class), any(), anyList());
    }

    @Test
    @DisplayName("executing the scheduled work should mark the run failed and pass the raw error for the store to bound")
    void run_whenFetchThrows_shouldMarkRunFailed() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(true);
        var failureMessage = "boom-".repeat(100);
        when(fetchSourceJobs.fetchAndSave(eq(USER_ID), eq(LinkedInAsyncFetchService.SOURCE_ID), any()))
                .thenThrow(new RuntimeException(failureMessage));

        service.start(USER_ID);
        submitted.get(0).run();

        verify(runStore).fail(
                eq(LinkedInAsyncFetchService.SOURCE_ID), any(Instant.class), eq(failureMessage), anyList());
        verify(runStore, never()).complete(anyString(), any(Instant.class), any(), anyList());
    }

    @Test
    @DisplayName("an Error escaping the executor thread must still release the stuck run (try/finally guard)")
    void run_whenErrorEscapes_shouldReleaseStuckRun() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(true);
        when(runStore.get(LinkedInAsyncFetchService.SOURCE_ID)).thenReturn(FetchRun.running(Instant.now()));
        when(fetchSourceJobs.fetchAndSave(eq(USER_ID), eq(LinkedInAsyncFetchService.SOURCE_ID), any()))
                .thenThrow(new AssertionError("boom"));

        service.start(USER_ID);

        assertThrows(AssertionError.class, () -> submitted.get(0).run());

        verify(runStore).fail(
                eq(LinkedInAsyncFetchService.SOURCE_ID),
                any(Instant.class),
                eq("unexpected error escaped the fetch"),
                anyList());
        verify(runStore, never()).complete(anyString(), any(Instant.class), any(), anyList());
    }

    @Test
    @DisplayName("executor rejection should fail the run instead of leaving it stuck in RUNNING")
    void start_whenExecutorRejects_shouldFailRunAndRethrow() {
        when(runStore.tryBegin(anyString(), any(Instant.class))).thenReturn(true);
        Executor rejectingExecutor = command -> { throw new RejectedExecutionException(); };
        service = new LinkedInAsyncFetchService(fetchSourceJobs, runStore, rejectingExecutor);

        assertThrows(RejectedExecutionException.class, () -> service.start(USER_ID));

        verify(runStore).fail(
                eq(LinkedInAsyncFetchService.SOURCE_ID),
                any(Instant.class),
                eq("executor rejected the fetch: RejectedExecutionException"),
                anyList());
    }

    @Test
    @DisplayName("status should read the current run snapshot from the store")
    void status_shouldDelegateToStore() {
        when(runStore.get(LinkedInAsyncFetchService.SOURCE_ID)).thenReturn(FetchRun.idle());

        var run = service.status();

        assertEquals(FetchRun.State.IDLE, run.state());
        verify(runStore).get(LinkedInAsyncFetchService.SOURCE_ID);
    }
}
