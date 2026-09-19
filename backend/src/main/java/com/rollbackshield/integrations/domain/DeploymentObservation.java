package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.DeploymentObservationId;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;

import java.time.Instant;
import java.util.Objects;

/**
 * A moment where a connected runtime was actually observed to be in a
 * particular deployment state. The release created from this observation
 * points back to it, so every release has a real origin.
 */
public record DeploymentObservation(
    DeploymentObservationId id,
    OrganizationId organizationId,
    ServiceId serviceId,
    IntegrationId integrationId,
    DeploymentIdentity identity,
    ReleaseId releaseId,
    Instant observedAt
) {

    public DeploymentObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public static DeploymentObservation observe(OrganizationId organizationId, ServiceId serviceId,
                                                IntegrationId integrationId, DeploymentIdentity identity) {
        return new DeploymentObservation(DeploymentObservationId.newId(), organizationId, serviceId,
            integrationId, identity, null, Instant.now());
    }

    public DeploymentObservation linkedTo(ReleaseId releaseId) {
        return new DeploymentObservation(id, organizationId, serviceId, integrationId, identity,
            releaseId, observedAt);
    }
}
