package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.Integration;

import java.util.Objects;

/**
 * Everything a connector call needs: the integration (configuration and
 * endpoint) plus credential material resolved for this call. Secrets live
 * only inside this object and are never persisted or logged.
 */
public record ConnectorContext(Integration integration, CredentialMaterial credentials) {

    public ConnectorContext {
        Objects.requireNonNull(integration, "integration");
        Objects.requireNonNull(credentials, "credentials");
    }

    public String config(String key, String defaultValue) {
        String value = integration.configurationValue(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public String endpoint() {
        return integration.endpoint();
    }
}
