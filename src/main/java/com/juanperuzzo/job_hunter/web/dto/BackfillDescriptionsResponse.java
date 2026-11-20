package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.BackfillJobDescriptionsUseCase;

/**
 * HTTP payload for the description backfill endpoint
 * ({@code docs/specs/infojobs-description-backfill.md}).
 */
public record BackfillDescriptionsResponse(int scanned, int updated, int stillShort, long lastId) {

    public static BackfillDescriptionsResponse from(BackfillJobDescriptionsUseCase.Result result) {
        return new BackfillDescriptionsResponse(
                result.scanned(), result.updated(), result.stillShort(), result.lastId());
    }
}
