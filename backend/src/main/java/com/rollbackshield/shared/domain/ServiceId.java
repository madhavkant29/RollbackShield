package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record ServiceId(UUID value) {

    public ServiceId {
        Objects.requireNonNull(value, "ServiceId value must not be null");
    }

    public static ServiceId newId() {
        return new ServiceId(UUID.randomUUID());
    }

    public static ServiceId of(String value) {
        return new ServiceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
