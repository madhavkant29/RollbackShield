package com.rollbackshield.enforcement.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A proposed write that a protected application is about to perform.
 * `attemptedValue` is intentionally a nullable String for the hackathon rule
 * set (§8) — numeric rules parse it, enum/forbidden rules compare it
 * directly, nullability rules check its absence.
 */
public record MutationRequest(
    String entity,
    String field,
    String attemptedValue
) {

    public MutationRequest {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(field, "field");
    }

    public Optional<String> value() {
        return Optional.ofNullable(attemptedValue);
    }
}
