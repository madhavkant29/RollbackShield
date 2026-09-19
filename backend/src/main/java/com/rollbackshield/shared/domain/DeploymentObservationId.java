package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record DeploymentObservationId(UUID value) {

    public DeploymentObservationId {
        Objects.requireNonNull(value, "DeploymentObservationId value must not be null");
    }

    public static DeploymentObservationId newId() {
        return new DeploymentObservationId(UUID.randomUUID());
    }

    public static DeploymentObservationId of(String value) {
        return new DeploymentObservationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
