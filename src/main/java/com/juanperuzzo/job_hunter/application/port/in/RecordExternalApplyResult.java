package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EmailDraft;

/**
 * Outcome of {@link RecordExternalApplyUseCase#record(Long, Long)}.
 *
 * @param created {@code true} when a brand-new marker row was persisted (the caller
 *                maps this to {@code 201 Created}); {@code false} on a replay of an
 *                existing SENT marker or when a pending/approved draft was superseded
 *                in place.
 */
public record RecordExternalApplyResult(EmailDraft draft, boolean created) {
}