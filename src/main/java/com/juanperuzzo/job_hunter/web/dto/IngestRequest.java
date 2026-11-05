package com.juanperuzzo.job_hunter.web.dto;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/jobs/ingest}
 * ({@code docs/specs/linkedin-posts-ingest.md}): permalink, post text,
 * author (AI-classifier context, not persisted) and the real post date when
 * the bot resolved it from the DOM (null → collection-date fallback).
 */
public record IngestRequest(
        String url,
        String text,
        String author,
        LocalDate postedAt
) {
}
