package com.rollbackshield.shared.domain;

/**
 * Monotonically increasing version of a distributed policy snapshot.
 * Used by the SDK's PolicyCache to detect staleness and by audit/telemetry
 * to correlate decisions with the exact rule set that produced them.
 */
public record PolicyVersion(long value) {

    public PolicyVersion {
        if (value < 0) {
            throw new IllegalArgumentException("PolicyVersion must be non-negative");
        }
    }

    public PolicyVersion next() {
        return new PolicyVersion(value + 1);
    }

    public boolean isNewerThan(PolicyVersion other) {
        return this.value > other.value;
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
