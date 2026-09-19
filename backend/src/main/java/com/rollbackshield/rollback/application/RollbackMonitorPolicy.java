package com.rollbackshield.rollback.application;

import java.time.Duration;

/**
 * How long the rollback executor waits for a runtime to converge, and how
 * often it checks. Configurable because ECS services with slow health checks
 * legitimately need longer than the default.
 */
public record RollbackMonitorPolicy(Duration timeout, Duration interval) {

    public RollbackMonitorPolicy {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("rollback monitor timeout must be positive");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("rollback monitor interval must be positive");
        }
    }

    public int maxAttempts() {
        return Math.max(1, (int) (timeout.toMillis() / interval.toMillis()));
    }
}
