package com.rollbackshield.integrations.domain;

import java.util.Objects;

/**
 * The concrete runtime revision a rollback will restore. Built from a
 * {@link DeploymentIdentity} after the artifact has been verified to exist;
 * constructed explicitly so a rollback executor never has to re-derive it
 * from mutable runtime state.
 */
public record RollbackTarget(
    String runtimeExternalId,
    String runtimeName,
    String targetRevision,
    String artifactDigest,
    String commitSha
) {

    public RollbackTarget {
        Objects.requireNonNull(runtimeExternalId, "runtimeExternalId");
        Objects.requireNonNull(runtimeName, "runtimeName");
        Objects.requireNonNull(targetRevision, "targetRevision");
    }
}
