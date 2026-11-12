package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.FetchLinkedInAsyncUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchResult;
import com.juanperuzzo.job_hunter.application.port.in.FetchRunStatusUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.FetchRun;
import com.juanperuzzo.job_hunter.application.port.out.FetchRunStore;
import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Async LinkedIn fetch (issue #116, linkedin-async-fetch spec): starts the
 * fetch+save on a dedicated single-thread executor and records run state in a
 * {@link FetchRunStore}. The userId is captured before the HTTP 202 so keyword
 * resolution uses the caller's identity (runtime keywords, issue #79 Phase 1).
 * Single-flight is enforced by the store — a second start while a run is
 * active is rejected before any work is scheduled.
 */
public class LinkedInAsyncFetchService implements FetchLinkedInAsyncUseCase, FetchRunStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(LinkedInAsyncFetchService.class);

    /** Only source with an async contract; every other fetch stays synchronous. */
    public static final String SOURCE_ID = "linkedin";

    private final FetchSourceJobsUseCase fetchSourceJobs;
    private final FetchRunStore runStore;
    private final Executor fetchExecutor;

    public LinkedInAsyncFetchService(
            FetchSourceJobsUseCase fetchSourceJobs,
            FetchRunStore runStore,
            Executor fetchExecutor) {
        this.fetchSourceJobs = fetchSourceJobs;
        this.runStore = runStore;
        this.fetchExecutor = fetchExecutor;
    }

    @Override
    public StartResult start(Long userId) {
        Objects.requireNonNull(userId, "userId");
        var startedAt = Instant.now();
        if (!runStore.tryBegin(SOURCE_ID, startedAt)) {
            // The 409 body reports the ACTIVE run's own startedAt, not now().
            return new StartResult(StartOutcome.ALREADY_RUNNING, runStore.get(SOURCE_ID).startedAt());
        }
        try {
            fetchExecutor.execute(() -> run(userId));
        } catch (RuntimeException e) {
            // Without this guard a rejected submission would leave the registry
            // RUNNING forever — every later POST a 409 until restart.
            log.error("{}: executor rejected the fetch: {}", SOURCE_ID, e.getMessage(), e);
            runStore.fail(SOURCE_ID, Instant.now(), "executor rejected the fetch: " + messageOf(e), List.of());
            throw e;
        }
        return new StartResult(StartOutcome.STARTED, startedAt);
    }

    @Override
    public FetchRun status() {
        return runStore.get(SOURCE_ID);
    }

    /**
     * Runs the fetch+save on the executor thread: keyword runs are collected
     * through the listener-aware use case overload and attached to the store on
     * completion or failure — whatever happens inside the scraper, the registry
     * reflects the actual outcome (saves continue past caller death by design).
     */
    private void run(Long userId) {
        var keywordRuns = Collections.synchronizedList(new ArrayList<KeywordRun>());
        try {
            var result = fetchSourceJobs.fetchAndSave(userId, SOURCE_ID, keywordRuns::add);
            finish(result, List.copyOf(keywordRuns));
        } catch (RuntimeException e) {
            log.error("{}: async fetch failed: {}", SOURCE_ID, e.getMessage(), e);
            runStore.fail(SOURCE_ID, Instant.now(), messageOf(e), List.copyOf(keywordRuns));
        } finally {
            releaseIfStillRunning();
        }
    }

    /**
     * The synchronous use case surfaces scraper failures as a provider error
     * inside a normal {@link FetchResult} ({@code FetchSourceJobsService} keeps
     * its own catch-all) — translate that here, or a dead fetch would report a
     * clean "completed" with zero counts (issue #116 review, 2026-11-12).
     */
    private void finish(FetchResult result, List<KeywordRun> keywordRuns) {
        var stats = result.perProvider().stream()
                .filter(provider -> SOURCE_ID.equals(provider.source()))
                .findFirst()
                .orElse(null);
        if (stats != null && stats.error() != null) {
            log.error("{}: provider reported failure: {}", SOURCE_ID, stats.error());
            runStore.fail(SOURCE_ID, Instant.now(), stats.error(), keywordRuns);
            return;
        }
        runStore.complete(SOURCE_ID, Instant.now(), result, keywordRuns);
        log.info("{}: async fetch completed - {} saved, {} with email",
                SOURCE_ID, result.totalSaved(), result.totalWithEmail());
    }

    /**
     * Spec Design: state is recorded around the fetch in try/finally — an
     * {@link Error} escaping the executor thread must never leave the run stuck
     * in RUNNING (permanent 409 until restart). Last-resort guard after both
     * normal paths above.
     */
    private void releaseIfStillRunning() {
        var snapshot = runStore.get(SOURCE_ID);
        if (snapshot != null && snapshot.state() == FetchRun.State.RUNNING) {
            log.error("{}: unexpected error escaped the fetch — releasing stuck run", SOURCE_ID);
            runStore.fail(SOURCE_ID, Instant.now(), "unexpected error escaped the fetch", List.of());
        }
    }

    private static String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
