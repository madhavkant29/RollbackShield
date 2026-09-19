package com.rollbackshield.integrations.application;

import com.rollbackshield.audit.adapter.InMemoryAuditTrail;
import com.rollbackshield.integrations.adapter.InMemoryDiscoveredResourceRepository;
import com.rollbackshield.integrations.adapter.InMemoryIntegrationRepository;
import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.SyncResult;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.api.ValidationException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.events.adapter.LoggingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationApplicationServiceTest {

    private static final OrganizationId ORG_A = OrganizationId.newId();
    private static final OrganizationId ORG_B = OrganizationId.newId();

    private InMemoryIntegrationRepository integrations;
    private InMemoryDiscoveredResourceRepository resources;
    private StubAwsConnector connector;
    private IntegrationApplicationService service;
    private IntegrationSyncService syncService;

    @BeforeEach
    void setUp() {
        integrations = new InMemoryIntegrationRepository();
        resources = new InMemoryDiscoveredResourceRepository();
        connector = new StubAwsConnector();
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector), List.of(connector));
        CredentialResolver credentials = reference -> switch (reference.kind()) {
            case AWS_CONTROL_PLANE_ROLE -> new CredentialMaterial.ControlPlaneRole();
            default -> new CredentialMaterial.None();
        };
        InMemoryAuditTrail audit = new InMemoryAuditTrail();
        LoggingEventPublisher events = new LoggingEventPublisher();
        syncService = new IntegrationSyncService(integrations, resources, registry, credentials, audit, events);
        service = new IntegrationApplicationService(integrations, registry, credentials, syncService, audit, events);
    }

    @Test
    void createRejectsCredentialKindsTheConnectorCannotUse() {
        CreateIntegrationCommand wrong = new CreateIntegrationCommand("github-token-on-aws",
            ConnectorType.AWS, "us-east-1",
            IntegrationCredentialReference.githubToken("SOME_ENV_VAR"), Map.of());

        ValidationException ex = assertThrows(ValidationException.class,
            () -> service.create(ORG_A, wrong));
        assertEquals("INVALID_CREDENTIAL_REFERENCE", ex.code());
    }

    @Test
    void connectionStateOnlyChangesFromARealConnectorResult() {
        Integration created = service.create(ORG_A, awsCommand());
        assertEquals("CONNECTING", created.connectionState().name());

        connector.testResult = ConnectionTestResult.failed("AccessDenied: not authorized", Map.of());
        Integration failed = service.testConnection(created.id(), ORG_A);
        assertEquals("ERROR", failed.connectionState().name());
        assertEquals("AccessDenied: not authorized", failed.lastError());

        connector.testResult = ConnectionTestResult.ok("sts:GetCallerIdentity ok", Map.of("account", "123"));
        Integration connected = service.testConnection(created.id(), ORG_A);
        assertEquals("CONNECTED", connected.connectionState().name());
        assertEquals(null, connected.lastError());
    }

    @Test
    void syncPersistsDiscoveredResourcesAndStampsSuccessfulSync() {
        Integration created = service.create(ORG_A, awsCommand());
        connector.discovered = List.of(
            resource(created, "payments", DiscoveredResourceType.RUNTIME_SERVICE),
            resource(created, "payments-cluster", DiscoveredResourceType.RUNTIME_CLUSTER));

        SyncResult result = service.sync(created.id(), ORG_A);

        assertEquals(2, result.discoveredCount());
        assertEquals(0, result.errorCount());
        assertEquals(2, resources.findByIntegration(created.id()).size());
        Integration reloaded = service.get(created.id(), ORG_A);
        assertNotNull(reloaded.lastSuccessfulSyncAt());
        assertEquals("CONNECTED", reloaded.connectionState().name());
        assertEquals("SUCCEEDED", reloaded.syncState().status().name());
        assertEquals(2, reloaded.syncState().lastDiscoveredCount());
        assertEquals(null, reloaded.lastError());
    }

    @Test
    void failedSyncDoesNotReplacePreviouslyDiscoveredResources() {
        Integration created = service.create(ORG_A, awsCommand());
        connector.discovered = List.of(resource(created, "payments", DiscoveredResourceType.RUNTIME_SERVICE));
        service.sync(created.id(), ORG_A);

        connector.failWith = new IllegalStateException("ecs:ListClusters unavailable");
        SyncResult result = service.sync(created.id(), ORG_A);

        assertEquals(1, result.errorCount());
        assertTrue(result.hasErrors());
        // The previous, real discovery result is preserved; no partial state is presented as current.
        assertEquals(1, resources.findByIntegration(created.id()).size());
        Integration reloaded = service.get(created.id(), ORG_A);
        assertTrue(reloaded.lastError().contains("ecs:ListClusters unavailable"));
        // A failed attempt must not erase the last real success.
        assertEquals("FAILED", reloaded.syncState().status().name());
        assertNotNull(reloaded.syncState().lastSuccessfulAt());
        assertEquals(1, reloaded.syncState().lastDiscoveredCount());
    }

    @Test
    void integrationsAreTenantScoped() {
        Integration created = service.create(ORG_A, awsCommand());

        assertThrows(NotFoundException.class, () -> service.get(created.id(), ORG_B));
        assertThrows(NotFoundException.class, () -> service.testConnection(created.id(), ORG_B));
        assertTrue(service.list(ORG_B).isEmpty());
        assertFalse(service.list(ORG_A).isEmpty());
    }

    private CreateIntegrationCommand awsCommand() {
        return new CreateIntegrationCommand("hackathon-account", ConnectorType.AWS, "us-east-1",
            IntegrationCredentialReference.awsControlPlaneRole(), Map.of());
    }

    private static DiscoveredResource resource(Integration integration, String name,
                                               DiscoveredResourceType type) {
        return DiscoveredResource.of(integration.id(), ConnectorType.AWS, type, name, name, "us-east-1",
            Map.of("source", "stub"));
    }

    /** Declares AWS capabilities; discovery behavior is switched per test. */
    static final class StubAwsConnector implements Connector, CapabilityProvider, DiscoveryContributionPort {

        ConnectionTestResult testResult = ConnectionTestResult.ok("ok", Map.of());
        List<DiscoveredResource> discovered = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public ConnectorType type() {
            return ConnectorType.AWS;
        }

        @Override
        public Set<ConnectorCapability> capabilities() {
            return Set.of(ConnectorCapability.RUNTIME_DISCOVERY, ConnectorCapability.ARTIFACT_DISCOVERY);
        }        @Override
        public java.util.Set<IntegrationCredentialReference.CredentialKind> supportedCredentialKinds() {
            return java.util.Set.of(IntegrationCredentialReference.CredentialKind.AWS_CONTROL_PLANE_ROLE,
                IntegrationCredentialReference.CredentialKind.AWS_ASSUME_ROLE);
        }

        @Override
        public ConnectionTestResult testConnection(ConnectorContext context) {
            return testResult;
        }

        @Override
        public List<DiscoveredResource> discover(ConnectorContext context) {
            if (failWith != null) {
                throw failWith;
            }
            return discovered;
        }
    }
}
