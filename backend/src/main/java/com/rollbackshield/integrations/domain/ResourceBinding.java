package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.ServiceId;

import java.time.Instant;
import java.util.Objects;

/**
 * One edge between a RollbackShield service and a discovered resource. Every
 * binding carries its evidence and an honest confidence; nothing in this
 * model lets a caller assert a relationship the discovery did not observe.
 */
public record ResourceBinding(
    IntegrationId integrationId,
    DiscoveredResourceType resourceType,
    String externalId,
    BindingRole role,
    MappingConfidence confidence,
    String evidence,
    Instant boundAt
) {

    public ResourceBinding {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(boundAt, "boundAt");
    }

    public enum BindingRole {
        REPOSITORY,
        RUNTIME,
        ARTIFACT_REPOSITORY,
        QUEUE,
        EVENT_BUS,
        DATABASE,
        MIGRATION_SOURCE
    }

    public enum MappingConfidence {
        /** Inferred from unambiguous provider data (e.g. ECS service names its ECR repository). */
        HIGH,
        /** Inferred from a heuristic that a human should confirm. */
        MEDIUM,
        /** Suggested by an integration result; the user must confirm before it is trusted. */
        REQUIRES_CONFIRMATION
    }
}
