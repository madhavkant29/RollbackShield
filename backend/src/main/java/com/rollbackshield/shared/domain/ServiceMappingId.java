package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record ServiceMappingId(UUID value) {

    public ServiceMappingId {
        Objects.requireNonNull(value, "ServiceMappingId value must not be null");
    }

    public static ServiceMappingId newId() {
        return new ServiceMappingId(UUID.randomUUID());
    }

    public static ServiceMappingId of(String value) {
        return new ServiceMappingId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
