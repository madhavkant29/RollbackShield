package com.rollbackshield.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record ContractId(UUID value) {

    public ContractId {
        Objects.requireNonNull(value, "ContractId value must not be null");
    }

    public static ContractId newId() {
        return new ContractId(UUID.randomUUID());
    }

    public static ContractId of(String value) {
        return new ContractId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
