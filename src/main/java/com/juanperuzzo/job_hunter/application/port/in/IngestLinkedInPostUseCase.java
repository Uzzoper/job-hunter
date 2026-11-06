package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Ingests one bot-collected LinkedIn feed post.
 * ({@code docs/specs/linkedin-posts-ingest.md}).
 */
public interface IngestLinkedInPostUseCase {

    /**
     * Runs the cheap → expensive pipeline: validate body, e-mail pre-check,
     * URL dedupe, AI classification, output validation, save.
     *
     * @param command the collected post
     * @return the ingest outcome; ignored variants carry no data
     * @throws IllegalArgumentException when the permalink/text fails validation
     * @throws com.juanperuzzo.job_hunter.domain.exception.AiException when the
     *         AI gateway fails (mapped to 502 retryable)
     */
    IngestResult ingest(IngestPostCommand command);
}
