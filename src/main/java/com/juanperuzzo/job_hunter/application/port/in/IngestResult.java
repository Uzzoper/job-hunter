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
        IngestResult.NotRelevant, IngestResult.InvalidExtraction, IngestResult.Saved {

    /** Post text carries no contact e-mail. */
    record NoContact() implements IngestResult {
    }

    /** Permalink already saved (Job identity is URL-based). */
    record Duplicate() implements IngestResult {
    }

    /** AI classified the post as not-a-vacancy. */
    record NotJob() implements IngestResult {
    }

    /**
     * Vacancy, but outside the channel's target: not a software-development role
     * or above junior level (amendment 2026-11-20). Ambiguous posts are saved —
     * only an explicit non-dev role or an explicit pleno/senior level rejects.
     */
    record NotRelevant() implements IngestResult {
    }

    /** AI output unusable (blank or over-cap title/company). */
    record InvalidExtraction() implements IngestResult {
    }

    /** Post stored; carries the persisted Job. */
    record Saved(Job job) implements IngestResult {
    }
}
