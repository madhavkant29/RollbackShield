package com.rollbackshield.reversibility.domain;

import java.util.Objects;

/**
 * Why a check failed. Severity drives the preflight verdict: BLOCKING means
 * rollback must not proceed, UNKNOWN/REVIEW means the answer is not a yes.
 */
public record ReversibilityBlocker(String code, String description, Severity severity) {

    public ReversibilityBlocker {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
    }

    /** Convenience for the common case: a failed check is a hard blocker. */
    public ReversibilityBlocker(String code, String description) {
        this(code, description, Severity.BLOCKING);
    }

    public enum Severity {
        BLOCKING,
        UNKNOWN,
        REVIEW
    }

    public static final String POLICY_EXPIRED = "POLICY_EXPIRED";
    public static final String INCOMPATIBLE_STATE_WRITTEN = "INCOMPATIBLE_STATE_WRITTEN";
    public static final String UNFENCED_WORK_PENDING = "UNFENCED_WORK_PENDING";
    public static final String NO_ACTIVE_CONTRACT = "NO_ACTIVE_CONTRACT";
    public static final String NO_RUNTIME_MAPPING = "NO_RUNTIME_MAPPING";
    public static final String RUNTIME_NOT_OBSERVABLE = "RUNTIME_NOT_OBSERVABLE";
    public static final String NO_ROLLBACK_TARGET = "NO_ROLLBACK_TARGET";
    public static final String ROLLBACK_ARTIFACT_MISSING = "ROLLBACK_ARTIFACT_MISSING";
    public static final String ARTIFACT_REPOSITORY_NOT_MAPPED = "ARTIFACT_REPOSITORY_NOT_MAPPED";
    public static final String ARTIFACT_IDENTITY_UNKNOWN = "ARTIFACT_IDENTITY_UNKNOWN";
    public static final String DESTRUCTIVE_DATABASE_MIGRATION = "DESTRUCTIVE_DATABASE_MIGRATION";
    public static final String DATABASE_MIGRATIONS_UNREVIEWED = "DATABASE_MIGRATIONS_UNREVIEWED";
    public static final String MIGRATION_ANALYSIS_UNAVAILABLE = "MIGRATION_ANALYSIS_UNAVAILABLE";
    public static final String HEALTH_VERIFICATION_UNAVAILABLE = "HEALTH_VERIFICATION_UNAVAILABLE";
    public static final String ROLLBACK_EXECUTION_UNSUPPORTED = "ROLLBACK_EXECUTION_UNSUPPORTED";
    public static final String RUNTIME_HEALTH_DEGRADED = "RUNTIME_HEALTH_DEGRADED";

    public static ReversibilityBlocker blocking(String code, String description) {
        return new ReversibilityBlocker(code, description, Severity.BLOCKING);
    }

    public static ReversibilityBlocker unknown(String code, String description) {
        return new ReversibilityBlocker(code, description, Severity.UNKNOWN);
    }

    public static ReversibilityBlocker review(String code, String description) {
        return new ReversibilityBlocker(code, description, Severity.REVIEW);
    }
}
