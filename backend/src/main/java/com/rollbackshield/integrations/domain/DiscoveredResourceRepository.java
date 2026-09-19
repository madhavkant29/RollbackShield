package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;

import java.util.List;

public interface DiscoveredResourceRepository {

    /** Replaces the stored resource set for an integration with the given discovery outcome. */
    void replaceForIntegration(IntegrationId integrationId, List<DiscoveredResource> resources);

    List<DiscoveredResource> findByIntegration(IntegrationId integrationId);

    List<DiscoveredResource> findByIntegrationAndType(IntegrationId integrationId, DiscoveredResourceType type);

    java.util.Optional<DiscoveredResource> findByExternalId(IntegrationId integrationId,
                                                            DiscoveredResourceType type, String externalId);

    /** Removes resources that were not seen by the latest sync (provider-side deletions). */
    int deleteNotIn(IntegrationId integrationId, List<String> resourceIds);
}
