package com.rollbackshield.release.domain;


/**
 * Thrown when a caller attempts a release state transition that ReleaseState
 * does not permit. Carries a stable error code (INVALID_RELEASE_TRANSITION)
 * so the API layer can return a structured error rather than a message string.
 */
public class ReleaseTransitionException extends RuntimeException {

    public static final String ERROR_CODE = "INVALID_RELEASE_TRANSITION";

    private final ReleaseState from;
    private final ReleaseState to;

    public ReleaseTransitionException(ReleaseState from, ReleaseState to) {
        super("Cannot transition release from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public ReleaseState from() {
        return from;
    }

    public ReleaseState to() {
        return to;
    }
}
