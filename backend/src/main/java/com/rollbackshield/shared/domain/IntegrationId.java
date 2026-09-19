package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record IntegrationId(UUID value) {

    public IntegrationId {
        Objects.requireNonNull(value, "IntegrationId value must not be null");
    }

    public static IntegrationId newId() {
        return new IntegrationId(UUID.randomUUID());
    }

    public static IntegrationId of(String value) {
        return new IntegrationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
