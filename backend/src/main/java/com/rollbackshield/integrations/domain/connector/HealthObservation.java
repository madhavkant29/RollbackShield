package com.rollbackshield.integrations.domain.connector;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An observation of a runtime's health after a rollback. UNKNOWN is a real,
 * reportable outcome -- a rollback is never marked verified from absent
 * health data.
 */
public record HealthObservation(HealthState state, String detail, Instant observedAt,
                                Map<String, String> metrics) {

    public HealthObservation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(observedAt, "observedAt");
        metrics = Map.copyOf(metrics == null ? Map.of() : metrics);
    }

    public enum HealthState {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    public static HealthObservation healthy(String detail, Map<String, String> metrics) {
        return new HealthObservation(HealthState.HEALTHY, detail, Instant.now(), metrics);
    }

    public static HealthObservation unknown(String detail) {
        return new HealthObservation(HealthState.UNKNOWN, detail, Instant.now(), Map.of());
    }
}
