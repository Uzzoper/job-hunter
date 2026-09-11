package com.juanperuzzo.job_hunter.unit.domain;

import com.juanperuzzo.job_hunter.domain.model.ApplicationLifecycle;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApplicationLifecycle mapping tests")
class ApplicationLifecycleMappingTest {

    // ── EmailStatus mapping ───────────────────────────────────────────
    // SENT markers carry no evidence: honest mapping is SUBMITTED, never
    // VERIFIED. Other email states live on another axis (stays outside).

    @Nested
    @DisplayName("fromEmailStatus")
    class FromEmailStatusTests {

        @Test
        @DisplayName("sent should map to submitted, never verified")
        void sent_shouldMapToSubmitted() {
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.SUBMITTED),
                    ApplicationLifecycle.fromEmailStatus(EmailStatus.SENT));
        }

        @Test
        @DisplayName("pending, approved and rejected should stay outside the mapping")
        void nonSentStates_shouldStayOutsideMapping() {
            assertTrue(ApplicationLifecycle.fromEmailStatus(EmailStatus.PENDING).isEmpty());
            assertTrue(ApplicationLifecycle.fromEmailStatus(EmailStatus.APPROVED).isEmpty());
            assertTrue(ApplicationLifecycle.fromEmailStatus(EmailStatus.REJECTED).isEmpty());
        }
    }

    // ── Verdict mapping ───────────────────────────────────────────────
    // Evidence tiers: Tier 1 (success URL + AX success text) is sufficient
    // for VERIFIED; screenshots are optional audit evidence.

    @Nested
    @DisplayName("fromVerdict")
    class FromVerdictTests {

        @Test
        @DisplayName("submit_ok with evidence should map to verified")
        void submitOkWithEvidence_shouldMapToVerified() {
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.VERIFIED),
                    ApplicationLifecycle.fromVerdict("SUBMIT_OK", true));
        }

        @Test
        @DisplayName("submit_ok without evidence should map to submitted")
        void submitOkWithoutEvidence_shouldMapToSubmitted() {
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.SUBMITTED),
                    ApplicationLifecycle.fromVerdict("SUBMIT_OK", false));
        }

        @Test
        @DisplayName("incomplete outcomes should map to incomplete")
        void incompleteOutcomes_shouldMapToIncomplete() {
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.INCOMPLETE),
                    ApplicationLifecycle.fromVerdict("INCOMPLETE", false));
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.INCOMPLETE),
                    ApplicationLifecycle.fromVerdict("SUBMIT_DONE_NO_EVIDENCE", false));
            assertEquals(
                    java.util.Optional.of(ApplicationLifecycle.INCOMPLETE),
                    ApplicationLifecycle.fromVerdict("confirm_declined", false));
        }

        @Test
        @DisplayName("auth_required should stay a distinct outcome with no transition")
        void authRequired_shouldStayDistinctWithNoTransition() {
            assertTrue(ApplicationLifecycle.fromVerdict("auth_required", false).isEmpty());
        }

        @Test
        @DisplayName("unknown outcomes should stay outside the mapping")
        void unknownOutcomes_shouldStayOutsideMapping() {
            assertTrue(ApplicationLifecycle.fromVerdict("SOMETHING_ELSE", false).isEmpty());
            assertTrue(ApplicationLifecycle.fromVerdict(null, false).isEmpty());
        }
    }
}
