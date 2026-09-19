package com.rollbackshield.integrations.application;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.api.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a (connector type, capability) pair to the provider that implements
 * it. No caller ever names a provider class; no caller does
 * {@code if (provider == ...)}. Missing providers and undeclared capabilities
 * are explicit errors.
 */
@Component
public class ConnectorRegistry {

    private final Map<ConnectorType, Connector> connectors = new EnumMap<>(ConnectorType.class);
    private final Map<ConnectorType, List<CapabilityProvider>> providersByType = new EnumMap<>(ConnectorType.class);

    public ConnectorRegistry(List<Connector> connectors, List<CapabilityProvider> providers) {
        for (Connector connector : connectors) {
            Connector previous = this.connectors.put(connector.type(), connector);
            if (previous != null) {
                throw new IllegalStateException("Two connectors declared for type " + connector.type()
                    + ": " + previous.getClass() + " and " + connector.getClass());
            }
        }
        Map<ConnectorType, List<CapabilityProvider>> grouped = new LinkedHashMap<>();
        for (CapabilityProvider provider : providers) {
            grouped.computeIfAbsent(provider.type(), key -> new java.util.ArrayList<>()).add(provider);
        }
        grouped.forEach((type, list) -> this.providersByType.put(type, List.copyOf(list)));
    }

    public Connector connector(ConnectorType type) {
        Connector connector = connectors.get(type);
        if (connector == null) {
            throw new NotFoundException("CONNECTOR_NOT_IMPLEMENTED",
                "No connector is implemented for " + type);
        }
        return connector;
    }

    public boolean isImplemented(ConnectorType type) {
        return connectors.containsKey(type);
    }

    /** Capabilities a type can actually serve, as declared by its providers. */
    public java.util.Set<ConnectorCapability> supportedCapabilities(ConnectorType type) {
        java.util.EnumSet<ConnectorCapability> capabilities = java.util.EnumSet.noneOf(ConnectorCapability.class);
        for (CapabilityProvider provider : providersByType.getOrDefault(type, List.of())) {
            capabilities.addAll(provider.capabilities());
        }
        if (connectors.containsKey(type)) {
            capabilities.addAll(connectors.get(type).capabilities());
        }
        return capabilities;
    }

    public List<CapabilityProvider> providersFor(ConnectorType type) {
        return providersByType.getOrDefault(type, List.of());
    }

    /**
     * Resolves the provider implementing {@code capability}, checking both the
     * declared capability and the expected port type so a wiring mistake is an
     * immediate, precise error.
     */
    public <T> T port(ConnectorType type, ConnectorCapability capability, Class<T> portType) {
        for (CapabilityProvider provider : providersFor(type)) {
            if (provider.capabilities().contains(capability) && portType.isInstance(provider)) {
                return portType.cast(provider);
            }
        }
        if (!isImplemented(type)) {
            throw new NotFoundException("CONNECTOR_NOT_IMPLEMENTED",
                "No connector is implemented for " + type);
        }
        throw new ConflictException("CAPABILITY_NOT_SUPPORTED",
            type + " does not support " + capability, Map.of("connectorType", type.name(),
                "capability", capability.name()));
    }

    /**
     * Resolves whichever registered provider implements {@code capability},
     * without the caller naming a connector type. Used by provider-neutral
     * core code (for example migration analysis).
     */
    public <T> T portForCapability(ConnectorCapability capability, Class<T> portType) {
        for (List<CapabilityProvider> providers : providersByType.values()) {
            for (CapabilityProvider provider : providers) {
                if (provider.capabilities().contains(capability) && portType.isInstance(provider)) {
                    return portType.cast(provider);
                }
            }
        }
        throw new NotFoundException("CONNECTOR_NOT_IMPLEMENTED",
            "No registered connector implements " + capability);
    }
}
