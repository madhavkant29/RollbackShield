package com.rollbackshield.release.domain;

import java.util.Set;

/**
 * Explicit lifecycle states for a protected release.
 *
 * Transitions are validated centrally via {@link #canTransitionTo(ReleaseState)}.
 * No other code in the system should mutate release state without going through
 * this check — see ReleaseStateMachine / ReleaseTransitionService in the
 * application layer.
 */
public enum ReleaseState {

    DRAFT(Set.of()),
    PREPARING(Set.of()),
    READY(Set.of()),
    PROTECTED_ROLLOUT(Set.of()),
    AT_RISK(Set.of()),
    ROLLING_BACK(Set.of()),
    ROLLED_BACK(Set.of()),
    COMMITTING(Set.of()),
    COMMITTED(Set.of()),
    FAILED(Set.of()),
    CANCELLED(Set.of());

    // Populated in static block below to allow forward references between enum constants.
    private static final Set<ReleaseState> NO_TRANSITIONS = Set.of();

    private final Set<ReleaseState> allowedTargets;

    ReleaseState(Set<ReleaseState> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    private static final java.util.Map<ReleaseState, Set<ReleaseState>> TRANSITIONS =
        java.util.Map.ofEntries(
            java.util.Map.entry(DRAFT, Set.of(PREPARING, CANCELLED)),
            java.util.Map.entry(PREPARING, Set.of(READY, FAILED, CANCELLED)),
            java.util.Map.entry(READY, Set.of(PROTECTED_ROLLOUT, CANCELLED)),
            java.util.Map.entry(PROTECTED_ROLLOUT, Set.of(AT_RISK, ROLLING_BACK, COMMITTING)),
            java.util.Map.entry(AT_RISK, Set.of(PROTECTED_ROLLOUT, ROLLING_BACK)),
            java.util.Map.entry(ROLLING_BACK, Set.of(ROLLED_BACK, FAILED)),
            java.util.Map.entry(ROLLED_BACK, Set.of()),
            java.util.Map.entry(COMMITTING, Set.of(COMMITTED, FAILED)),
            // COMMITTED is terminal-forward: it must never return to ROLLING_BACK (§5 of spec).
            java.util.Map.entry(COMMITTED, Set.of()),
            java.util.Map.entry(FAILED, Set.of()),
            java.util.Map.entry(CANCELLED, Set.of())
        );

    public boolean canTransitionTo(ReleaseState target) {
        return TRANSITIONS.getOrDefault(this, NO_TRANSITIONS).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, NO_TRANSITIONS).isEmpty();
    }

    public boolean isProtected() {
        return this == PROTECTED_ROLLOUT || this == AT_RISK;
    }
}
