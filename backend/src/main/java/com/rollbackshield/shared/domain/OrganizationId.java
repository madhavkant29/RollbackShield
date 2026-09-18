package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record OrganizationId(UUID value) {

    public OrganizationId {
        Objects.requireNonNull(value, "OrganizationId value must not be null");
    }

    public static OrganizationId newId() {
        return new OrganizationId(UUID.randomUUID());
    }

    public static OrganizationId of(String value) {
        return new OrganizationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
