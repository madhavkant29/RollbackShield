package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;

import java.util.Set;

/**
 * Base contract for every connector. A connector is the only place a
 * provider SDK may appear; it exposes capability ports that the application
 * layer invokes by declared capability, never by provider-specific branching.
 */
public interface Connector {

    ConnectorType type();

    /** The capabilities this connector genuinely implements. */
    Set<ConnectorCapability> capabilities();

    /**
     * Credential reference kinds this connector can actually use. Declared by
     * the connector, so the application layer validates credentials without
     * knowing any provider by name.
     */
    default Set<IntegrationCredentialReference.CredentialKind> supportedCredentialKinds() {
        return Set.of(IntegrationCredentialReference.CredentialKind.NONE);
    }

    /** Whether the integration must supply an endpoint (e.g. an AWS region). */
    default boolean requiresEndpoint() {
        return false;
    }

    /**
     * Performs a real call against the provider and reports what happened.
     * Implementations must not return success from configuration inspection.
     */
    ConnectionTestResult testConnection(ConnectorContext context);

    default boolean supports(ConnectorCapability capability) {
        return capabilities().contains(capability);
    }
}
