package com.juanperuzzo.job_hunter.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;

/**
 * HTTP payload for the backfill endpoints (contact emails and company websites):
 * scan statistics in the same shape as the use-case result ({@code scanned},
 * {@code filled}, {@code stillNull}).
 */
public record BackfillResultResponse(int scanned, int filled, int stillNull) {

    public static BackfillResultResponse from(BackfillContactEmailsUseCase.Result result) {
        return new BackfillResultResponse(result.scanned(), result.filled(), result.stillNull());
    }

    public static BackfillResultResponse from(BackfillCompanyWebsitesUseCase.Result result) {
        return new BackfillResultResponse(result.scanned(), result.filled(), result.stillNull());
    }
}