package com.juanperuzzo.job_hunter.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit application lifecycle (issue #56).
 * <p>
 * Collapsed states caused the phantom-application bug: a plan-time record
 * masquerading as done. Skipping a stage is unrepresentable here —
 * <ul>
 *   <li>{@code SUBMITTED} = we executed the submit</li>
 *   <li>{@code VERIFIED} = the portal confirmed the application (evidence required)</li>
 * </ul>
 * Pure Java, zero framework imports.
 */
public enum ApplicationLifecycle {
    PLANNED,
    IN_PROGRESS,
    SUBMITTED,
    VERIFIED,
    FAILED,
    INCOMPLETE,
    VERIFICATION_FAILED;

    private static final Map<ApplicationLifecycle, Set<ApplicationLifecycle>> TRANSITIONS;

    static {
        Map<ApplicationLifecycle, Set<ApplicationLifecycle>> transitions = new EnumMap<>(ApplicationLifecycle.class);
        transitions.put(PLANNED, EnumSet.of(IN_PROGRESS));
        transitions.put(IN_PROGRESS, EnumSet.of(SUBMITTED, FAILED, INCOMPLETE));
        transitions.put(SUBMITTED, EnumSet.of(VERIFIED, VERIFICATION_FAILED));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * Whether this state may legally move to {@code target}. Terminal states
     * have no outgoing transitions; a {@code null} target is never allowed.
     */
    public boolean canTransitionTo(ApplicationLifecycle target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * Whether this state ends the lifecycle (no outgoing transitions).
     */
    public boolean isTerminal() {
        return !TRANSITIONS.containsKey(this);
    }

    /**
     * Honest translation from email states. {@code SENT} markers carry no
     * evidence, so they map to {@code SUBMITTED} — never {@code VERIFIED}.
     * Other email states live on another axis and stay outside the mapping
     * (empty).
     */
    public static Optional<ApplicationLifecycle> fromEmailStatus(EmailStatus status) {
        if (status == EmailStatus.SENT) {
            return Optional.of(SUBMITTED);
        }
        return Optional.empty();
    }

    /**
     * Translation from executor verdict outcomes. Tier-1 evidence (success URL
     * plus AX success text) is sufficient for {@code VERIFIED}; screenshots
     * are optional audit evidence. {@code auth_required} stays a distinct
     * outcome with no transition (the application remains wherever it was).
     * Unknown outcomes stay outside the mapping (empty).
     *
     * @param outcome the verdict outcome label (e.g. {@code SUBMIT_OK})
     * @param verifiedEvidence whether Tier-1 evidence was captured
     */
    public static Optional<ApplicationLifecycle> fromVerdict(String outcome, boolean verifiedEvidence) {
        if (outcome == null) {
            return Optional.empty();
        }
        return switch (outcome) {
            case "SUBMIT_OK" -> Optional.of(verifiedEvidence ? VERIFIED : SUBMITTED);
            case "INCOMPLETE", "SUBMIT_DONE_NO_EVIDENCE", "confirm_declined",
                 "budget_exceeded", "loop_stalled", "error" -> Optional.of(INCOMPLETE);
            default -> Optional.empty();
        };
    }
}
