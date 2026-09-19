package com.rollbackshield.integrations.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Last known health of a connection, with the detail needed to act on it.
 * Never synthesised: produced by a connector call or marked UNKNOWN.
 */
public record ConnectorHealth(HealthState state, String detail, Instant checkedAt) {

    public ConnectorHealth {
        Objects.requireNonNull(state, "state");
    }

    public enum HealthState {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    public static ConnectorHealth unknown(String detail) {
        return new ConnectorHealth(HealthState.UNKNOWN, detail, null);
    }
}
