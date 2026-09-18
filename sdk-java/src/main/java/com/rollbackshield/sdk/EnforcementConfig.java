package com.rollbackshield.sdk;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link RollbackGuard} should behave, independently configurable per
 * call site because risk varies by mutation (§11): a financial write might
 * use ENFORCE + FAIL_CLOSED while low-impact metadata uses WARN + FAIL_OPEN.
 */
public record EnforcementConfig(
    Mode mode,
    FailureBehavior failureBehavior,
    Duration staleAfter,
    Duration expireAfter
) {

    public EnforcementConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(failureBehavior, "failureBehavior");
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(expireAfter, "expireAfter");
        if (staleAfter.compareTo(expireAfter) > 0) {
            throw new IllegalArgumentException("staleAfter must not exceed expireAfter");
        }
    }

    public static EnforcementConfig enforceFailClosed() {
        return new EnforcementConfig(Mode.ENFORCE, FailureBehavior.FAIL_CLOSED,
            Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    public static EnforcementConfig warnFailOpen() {
        return new EnforcementConfig(Mode.WARN, FailureBehavior.FAIL_OPEN,
            Duration.ofMinutes(2), Duration.ofMinutes(15));
    }

    public enum Mode { OBSERVE, WARN, ENFORCE }

    public enum FailureBehavior { FAIL_OPEN, FAIL_CLOSED }
}
