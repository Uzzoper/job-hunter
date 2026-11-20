package com.juanperuzzo.job_hunter.application.port.out;

import java.util.Optional;

/**
 * Fetches the full description of a stored job from its source detail page
 * ({@code docs/specs/infojobs-description-backfill.md}).
 *
 * <p>Deliberately description-only: the backfill repairs text, nothing else.
 * Implementations are expected to swallow transport failures and answer
 * {@link Optional#empty()} — one dead URL must not abort a batch.
 */
public interface JobDescriptionSourcePort {

    /**
     * @return the description body, or {@link Optional#empty()} when the page has
     *         none, is a bot-challenge page, or cannot be fetched
     */
    Optional<String> fetchDescription(String url);
}
