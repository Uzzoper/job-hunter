package com.juanperuzzo.job_hunter.application.port.in;

import java.time.LocalDate;

/**
 * Command carrying everything the bot collected from a single LinkedIn feed
 * post ({@code docs/specs/linkedin-posts-ingest.md}). {@code author} is
 * context for the AI classification only — it is not persisted. {@code
 * postedAt} is the real post date when the DOM showed one; null falls back
 * to the collection date in the use case.
 */
public record IngestPostCommand(
        String url,
        String text,
        String author,
        LocalDate postedAt
) {
}
