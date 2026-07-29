package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;

import java.util.Optional;

public interface AutoSendEligibilityUseCase {
    Optional<EligibleDraft> nextEligibleDraft();
}
