package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.DeploymentIdentity;

import java.util.Optional;

/**
 * Reads the current and previous deployment of a runtime as the provider
 * reports it. Implementations must return empty rather than inventing a
 * previous revision.
 */
public interface DeploymentObservationPort {

    Optional<DeploymentIdentity> observeDeployment(ConnectorContext context, String runtimeExternalId);
}
