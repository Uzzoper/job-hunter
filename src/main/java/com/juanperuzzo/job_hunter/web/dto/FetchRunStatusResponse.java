package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.out.FetchRun;
import com.juanperuzzo.job_hunter.application.port.out.KeywordRun;
import com.juanperuzzo.job_hunter.application.service.LinkedInAsyncFetchService;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /api/jobs/fetch/linkedin/status} (issue #116): run state
 * plus counts and per-keyword outcomes. Count fields stay null while the run
 * is idle/running — they carry the completed run's aggregate afterwards.
 */
public record FetchRunStatusResponse(
        String state,
        Instant startedAt,
        Instant finishedAt,
        Integer fetched,
        Integer saved,
        Integer withEmail,
        Integer detailFailed,
        Integer detailSkipped,
        String error,
        List<KeywordRunResponse> keywords) {

    public record KeywordRunResponse(String term, String status, int jobsFound, long durationMs) {

        public static KeywordRunResponse from(KeywordRun run) {
            return new KeywordRunResponse(run.term(), run.status().code(), run.jobsFound(), run.durationMs());
        }
    }

    public static FetchRunStatusResponse from(FetchRun run) {
        var stats = run.result() == null
                ? null
                : run.result().perProvider().stream()
                        .filter(provider -> LinkedInAsyncFetchService.SOURCE_ID.equals(provider.source()))
                        .findFirst()
                        .orElse(null);
        return new FetchRunStatusResponse(
                run.state().name().toLowerCase(),
                run.startedAt(),
                run.finishedAt(),
                stats == null ? null : stats.fetched(),
                stats == null ? null : stats.saved(),
                stats == null ? null : stats.withEmail(),
                stats == null ? null : stats.detailFailedCount(),
                stats == null ? null : stats.detailSkippedCount(),
                run.error(),
                run.keywordRuns().stream().map(KeywordRunResponse::from).toList());
    }
}
