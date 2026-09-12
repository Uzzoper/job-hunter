package com.juanperuzzo.job_hunter.unit.domain;

import com.juanperuzzo.job_hunter.domain.model.ApplicationLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApplicationLifecycle transition tests")
class ApplicationLifecycleTest {

    // ── Happy path ────────────────────────────────────────────────────
    // PLANNED → IN_PROGRESS → SUBMITTED → VERIFIED. Skipping a stage must
    // be unrepresentable: SUBMITTED means executed, VERIFIED means confirmed.

    @Nested
    @DisplayName("Happy path")
    class HappyPathTests {

        @Test
        @DisplayName("planned should transition to in_progress")
        void planned_shouldTransitionToInProgress() {
            assertTrue(ApplicationLifecycle.PLANNED.canTransitionTo(ApplicationLifecycle.IN_PROGRESS));
        }

        @Test
        @DisplayName("in_progress should transition to submitted")
        void inProgress_shouldTransitionToSubmitted() {
            assertTrue(ApplicationLifecycle.IN_PROGRESS.canTransitionTo(ApplicationLifecycle.SUBMITTED));
        }

        @Test
        @DisplayName("submitted should transition to verified")
        void submitted_shouldTransitionToVerified() {
            assertTrue(ApplicationLifecycle.SUBMITTED.canTransitionTo(ApplicationLifecycle.VERIFIED));
        }
    }

    // ── Failure paths ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Failure paths")
    class FailurePathTests {

        @Test
        @DisplayName("in_progress should transition to failed")
        void inProgress_shouldTransitionToFailed() {
            assertTrue(ApplicationLifecycle.IN_PROGRESS.canTransitionTo(ApplicationLifecycle.FAILED));
        }

        @Test
        @DisplayName("in_progress should transition to incomplete")
        void inProgress_shouldTransitionToIncomplete() {
            assertTrue(ApplicationLifecycle.IN_PROGRESS.canTransitionTo(ApplicationLifecycle.INCOMPLETE));
        }

        @Test
        @DisplayName("submitted should transition to verification_failed")
        void submitted_shouldTransitionToVerificationFailed() {
            assertTrue(ApplicationLifecycle.SUBMITTED.canTransitionTo(ApplicationLifecycle.VERIFICATION_FAILED));
        }
    }

    // ── Illegal transitions ───────────────────────────────────────────
    // The phantom-application bug was a skipped stage: planned masquerading
    // as done. Direct jumps must be rejected.

    @Nested
    @DisplayName("Illegal transitions")
    class IllegalTransitionTests {

        @Test
        @DisplayName("planned should never transition directly to verified")
        void planned_shouldNeverTransitionDirectlyToVerified() {
            assertFalse(ApplicationLifecycle.PLANNED.canTransitionTo(ApplicationLifecycle.VERIFIED));
        }

        @Test
        @DisplayName("planned should never transition directly to submitted")
        void planned_shouldNeverTransitionDirectlyToSubmitted() {
            assertFalse(ApplicationLifecycle.PLANNED.canTransitionTo(ApplicationLifecycle.SUBMITTED));
        }

        @Test
        @DisplayName("in_progress should never transition directly to verified")
        void inProgress_shouldNeverTransitionDirectlyToVerified() {
            assertFalse(ApplicationLifecycle.IN_PROGRESS.canTransitionTo(ApplicationLifecycle.VERIFIED));
        }

        @Test
        @DisplayName("submitted should never go back to in_progress")
        void submitted_shouldNeverGoBackToInProgress() {
            assertFalse(ApplicationLifecycle.SUBMITTED.canTransitionTo(ApplicationLifecycle.IN_PROGRESS));
        }

        @Test
        @DisplayName("terminal states should have no outgoing transitions")
        void terminalStates_shouldHaveNoOutgoingTransitions() {
            for (var terminal : new ApplicationLifecycle[]{
                    ApplicationLifecycle.VERIFIED,
                    ApplicationLifecycle.FAILED,
                    ApplicationLifecycle.INCOMPLETE,
                    ApplicationLifecycle.VERIFICATION_FAILED}) {
                for (var target : ApplicationLifecycle.values()) {
                    assertFalse(terminal.canTransitionTo(target),
                            () -> terminal + " should not transition to " + target);
                }
            }
        }
    }

    // ── Terminal states ───────────────────────────────────────────────

    @Nested
    @DisplayName("Terminal states")
    class TerminalStateTests {

        @Test
        @DisplayName("verified, failed, incomplete and verification_failed should be terminal")
        void endStates_shouldBeTerminal() {
            assertTrue(ApplicationLifecycle.VERIFIED.isTerminal());
            assertTrue(ApplicationLifecycle.FAILED.isTerminal());
            assertTrue(ApplicationLifecycle.INCOMPLETE.isTerminal());
            assertTrue(ApplicationLifecycle.VERIFICATION_FAILED.isTerminal());
        }

        @Test
        @DisplayName("planned, in_progress and submitted should not be terminal")
        void openStates_shouldNotBeTerminal() {
            assertFalse(ApplicationLifecycle.PLANNED.isTerminal());
            assertFalse(ApplicationLifecycle.IN_PROGRESS.isTerminal());
            assertFalse(ApplicationLifecycle.SUBMITTED.isTerminal());
        }
    }
}
