package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A resource found in a connected system (an ECS service, an ECR image, a
 * queue, a repository...). Discovered resources are evidence: they carry the
 * provider's external id and the metadata the provider returned, not an
 * inferred model of what the user might want.
 */
public record DiscoveredResource(
    String resourceId,
    IntegrationId integrationId,
    ConnectorType connectorType,
    DiscoveredResourceType resourceType,
    String externalId,
    String displayName,
    String region,
    Map<String, String> metadata,
    Instant discoveredAt
) {

    public DiscoveredResource {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(connectorType, "connectorType");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(displayName, "displayName");
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static DiscoveredResource of(IntegrationId integrationId, ConnectorType connectorType,
                                        DiscoveredResourceType resourceType, String externalId,
                                        String displayName, String region, Map<String, String> metadata) {
        return new DiscoveredResource(stableId(integrationId, resourceType, externalId), integrationId,
            connectorType, resourceType, externalId, displayName, region, metadata, Instant.now());
    }

    private static String stableId(IntegrationId integrationId, DiscoveredResourceType type, String externalId) {
        return integrationId + ":" + type.name() + ":" + externalId;
    }
}
