package com.rollbackshield.integrations.application;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.api.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorRegistryTest {

    private final FakeAwsConnector aws = new FakeAwsConnector();

    @Test
    void routesCapabilityToTheProviderThatDeclaresIt() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(aws), List.of(aws));

        DeploymentObservationPort port = registry.port(ConnectorType.AWS,
            ConnectorCapability.DEPLOYMENT_STATUS, DeploymentObservationPort.class);
        assertEquals(aws, port);
        assertTrue(registry.supportedCapabilities(ConnectorType.AWS)
            .contains(ConnectorCapability.DEPLOYMENT_STATUS));
    }

    @Test
    void undeclaredCapabilityIsAnExplicitConflict() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(aws), List.of(aws));

        assertThrows(ConflictException.class, () ->
            registry.port(ConnectorType.AWS, ConnectorCapability.SOURCE_DISCOVERY,
                DeploymentObservationPort.class));
    }

    @Test
    void unimplementedConnectorTypeIsAnExplicitNotFound() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), List.of());

        assertThrows(NotFoundException.class, () ->
            registry.port(ConnectorType.KUBERNETES, ConnectorCapability.DEPLOYMENT_STATUS,
                DeploymentObservationPort.class));
    }

    @Test
    void duplicateConnectorTypeFailsFastAtWiringTime() {
        FakeAwsConnector second = new FakeAwsConnector();
        assertThrows(IllegalStateException.class,
            () -> new ConnectorRegistry(List.of(aws, second), List.of(aws)));
    }

    static final class FakeAwsConnector implements Connector, CapabilityProvider, DeploymentObservationPort {

        @Override
        public ConnectorType type() {
            return ConnectorType.AWS;
        }

        @Override
        public Set<ConnectorCapability> capabilities() {
            return Set.of(ConnectorCapability.DEPLOYMENT_STATUS, ConnectorCapability.RUNTIME_DISCOVERY);
        }

        @Override
        public com.rollbackshield.integrations.domain.ConnectionTestResult testConnection(
            com.rollbackshield.integrations.domain.connector.ConnectorContext context) {
            return com.rollbackshield.integrations.domain.ConnectionTestResult.ok("fake", java.util.Map.of());
        }

        @Override
        public Optional<com.rollbackshield.integrations.domain.DeploymentIdentity> observeDeployment(
            com.rollbackshield.integrations.domain.connector.ConnectorContext context,
            String runtimeExternalId) {
            return Optional.empty();
        }
    }
}
