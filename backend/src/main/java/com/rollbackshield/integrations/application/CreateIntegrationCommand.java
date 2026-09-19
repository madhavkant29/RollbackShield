package com.rollbackshield.integrations.application;

import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;

import java.util.Map;
import java.util.Objects;

public record CreateIntegrationCommand(
    String name,
    ConnectorType type,
    String endpoint,
    IntegrationCredentialReference credential,
    Map<String, String> configuration
) {

    public CreateIntegrationCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(credential, "credential");
        configuration = Map.copyOf(configuration == null ? Map.of() : configuration);
    }
}
