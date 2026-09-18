package com.rollbackshield.sdk;

import java.util.Objects;

public record MutationRequest(String entity, String field, String attemptedValue) {
    public MutationRequest {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(field, "field");
    }
}
