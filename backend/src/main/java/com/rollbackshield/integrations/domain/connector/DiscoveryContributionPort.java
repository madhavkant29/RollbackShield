package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.DiscoveredResource;

import java.util.List;

/**
 * Implemented by every provider that contributes resources to a sync. The
 * sync service iterates these without knowing which capability each one
 * represents, so adding a provider never changes the sync loop.
 */
public interface DiscoveryContributionPort {

    List<DiscoveredResource> discover(ConnectorContext context);
}
