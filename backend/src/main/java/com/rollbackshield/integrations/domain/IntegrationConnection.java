package com.rollbackshield.integrations.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The connection aspect of an integration: whether the last real provider
 * call succeeded, what health was observed, and why it failed. Only a
 * {@link ConnectionTestResult} may move this to CONNECTED -- configuration
 * alone never does.
 */
public record IntegrationConnection(
    ConnectionState state,
    ConnectorHealth health,
    String lastError,
    Instant stateChangedAt
) {

    public IntegrationConnection {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(stateChangedAt, "stateChangedAt");
    }

    public static IntegrationConnection pending(Instant now) {
        return new IntegrationConnection(ConnectionState.CONNECTING,
            ConnectorHealth.unknown("connection test has not run yet"), null, now);
    }

    public IntegrationConnection withTestResult(ConnectionTestResult result) {
        ConnectionState nextState = result.success() ? ConnectionState.CONNECTED : ConnectionState.ERROR;
        ConnectorHealth nextHealth = new ConnectorHealth(
            result.success() ? ConnectorHealth.HealthState.HEALTHY : ConnectorHealth.HealthState.UNHEALTHY,
            result.message(), result.checkedAt());
        return new IntegrationConnection(nextState, nextHealth,
            result.success() ? null : result.message(), result.checkedAt());
    }

    /** Replaces observed health without changing the connection state (sync outcomes). */
    public IntegrationConnection withHealth(ConnectorHealth nextHealth, Instant now) {
        return new IntegrationConnection(state, nextHealth, lastError, now);
    }

    public IntegrationConnection asConnected(ConnectorHealth nextHealth, Instant now) {
        return new IntegrationConnection(ConnectionState.CONNECTED, nextHealth, null, now);
    }

    public IntegrationConnection disconnected(Instant now) {
        return new IntegrationConnection(ConnectionState.DISCONNECTED,
            ConnectorHealth.unknown("disconnected by operator"), null, now);
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }
}
