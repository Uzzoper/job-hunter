package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

/**
 * Outcome of the LinkedIn post ingest pipeline
 * ({@code docs/specs/linkedin-posts-ingest.md}). The web layer maps each
 * variant to its HTTP contract; filters discard early — cheap → expensive —
 * so an ignored post never reaches the AI.
 */
public sealed interface IngestResult
        permits IngestResult.NoContact, IngestResult.Duplicate, IngestResult.NotJob,
        IngestResult.InvalidExtraction, IngestResult.Saved {

    /** Post text carries no contact e-mail. */
    record NoContact() implements IngestResult {
    }

    /** Permalink already saved (Job identity is URL-based). */
    record Duplicate() implements IngestResult {
    }

    /** AI classified the post as not-a-vacancy. */
    record NotJob() implements IngestResult {
    }

    /** AI output unusable (blank or over-cap title/company). */
    record InvalidExtraction() implements IngestResult {
    }

    /** Post stored; carries the persisted Job. */
    record Saved(Job job) implements IngestResult {
    }
}
