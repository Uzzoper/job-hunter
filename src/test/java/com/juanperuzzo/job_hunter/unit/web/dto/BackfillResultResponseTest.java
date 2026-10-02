package com.juanperuzzo.job_hunter.unit.web.dto;

import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;
import com.juanperuzzo.job_hunter.web.dto.BackfillResultResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization tests for {@link BackfillResultResponse}: both use-case results
 * share the same three-field shape, so the mapper is shared and the HTTP payload
 * must stay identical whichever endpoint produced it.
 */
@DisplayName("BackfillResultResponse tests")
class BackfillResultResponseTest {

    @Test
    @DisplayName("from should map a company-websites result into the scanned/filled/stillNull payload")
    void from_whenCompanyWebsitesResult_shouldMapAllFields() {
        var response = BackfillResultResponse.from(new BackfillCompanyWebsitesUseCase.Result(10, 6, 4));

        assertEquals(10, response.scanned());
        assertEquals(6, response.filled());
        assertEquals(4, response.stillNull());
    }

    @Test
    @DisplayName("from should map a contact-emails result into the same payload shape")
    void from_whenContactEmailsResult_shouldMapAllFields() {
        var response = BackfillResultResponse.from(new BackfillContactEmailsUseCase.Result(7, 2, 5));

        assertEquals(7, response.scanned());
        assertEquals(2, response.filled());
        assertEquals(5, response.stillNull());
    }

    @Test
    @DisplayName("from should produce an identical payload for both use-case results with the same counts")
    void from_whenBothResultsShareCounts_shouldProduceIdenticalPayload() {
        var websites = BackfillResultResponse.from(new BackfillCompanyWebsitesUseCase.Result(3, 1, 2));
        var emails = BackfillResultResponse.from(new BackfillContactEmailsUseCase.Result(3, 1, 2));

        assertEquals(emails, websites,
                "the shared mapper must not let the two endpoints drift apart");
    }
}