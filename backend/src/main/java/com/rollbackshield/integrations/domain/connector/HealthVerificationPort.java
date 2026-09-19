package com.rollbackshield.integrations.domain.connector;

/** Verifies a runtime's health from the provider's own data. */
public interface HealthVerificationPort {

    HealthObservation verifyHealth(ConnectorContext context, String runtimeExternalId);
}
