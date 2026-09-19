package com.rollbackshield.integrations.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.api.ValidationException;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration lifecycle: create, inspect, connection-test, sync, disconnect,
 * delete. Every state change that the UI reports as real (CONNECTED, last
 * successful sync) is produced here from an actual connector call.
 */
@Service
public class IntegrationApplicationService {

    private final IntegrationRepository integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final IntegrationSyncService syncService;
    private final AuditTrail auditTrail;
    private final EventPublisher events;

    public IntegrationApplicationService(IntegrationRepository integrations, ConnectorRegistry connectors,
                                         CredentialResolver credentials, IntegrationSyncService syncService,
                                         AuditTrail auditTrail, EventPublisher events) {
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
        this.syncService = syncService;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    public Integration create(OrganizationId organizationId, CreateIntegrationCommand command) {
        validate(organizationId, command);

        Integration integration = Integration.pending(organizationId, command.name(), command.type(),
            command.endpoint(), command.credential(), command.configuration());
        integrations.save(integration);
        auditTrail.append(AuditEvent.of(organizationId.toString(), integration.id().toString(), "user",
            AuditAction.INTEGRATION_CREATED, integration.id().toString(),
            command.type() + " integration " + command.name(), null, "CONNECTING", Map.of()));
        events.publish(DomainEvent.of("IntegrationCreated", organizationId.toString(),
            integration.id().toString(), Map.of("connectorType", command.type().name())));
        return integration;
    }

    public List<Integration> list(OrganizationId organizationId) {
        return integrations.findByOrganization(organizationId);
    }

    public Integration get(IntegrationId id, OrganizationId callerOrganizationId) {
        Integration integration = integrations.findById(id)
            .orElseThrow(() -> new NotFoundException("INTEGRATION_NOT_FOUND", "No integration " + id));
        if (!integration.organizationId().equals(callerOrganizationId)) {
            throw new NotFoundException("INTEGRATION_NOT_FOUND", "No integration " + id);
        }
        return integration;
    }

    public Integration testConnection(IntegrationId id, OrganizationId callerOrganizationId) {
        Integration integration = get(id, callerOrganizationId);
        Connector connector = connectors.connector(integration.type());

        ConnectionTestResult result;
        try {
            CredentialMaterial material = credentials.resolve(integration.credential());
            result = connector.testConnection(new ConnectorContext(integration, material));
        } catch (RuntimeException e) {
            result = ConnectionTestResult.failed(safeMessage(e), Map.of());
        }

        Integration updated = integration.withConnectionResult(result);
        integrations.save(updated);
        auditTrail.append(AuditEvent.of(updated.organizationId().toString(), id.toString(), "user",
            result.success() ? AuditAction.INTEGRATION_CONNECTED : AuditAction.INTEGRATION_CONNECTION_FAILED,
            id.toString(), result.message(), integration.connectionState().name(),
            updated.connectionState().name(), Map.of()));
        events.publish(DomainEvent.of(result.success() ? "IntegrationConnected" : "IntegrationConnectionFailed",
            updated.organizationId().toString(), id.toString(), Map.of("connectorType", updated.type().name())));
        return updated;
    }

    public com.rollbackshield.integrations.domain.SyncResult sync(IntegrationId id,
                                                                  OrganizationId callerOrganizationId) {
        Integration integration = get(id, callerOrganizationId);
        return syncService.sync(integration);
    }

    public Integration disconnect(IntegrationId id, OrganizationId callerOrganizationId) {
        Integration integration = get(id, callerOrganizationId);
        Integration updated = integration.disconnect();
        integrations.save(updated);
        auditTrail.append(AuditEvent.of(updated.organizationId().toString(), id.toString(), "user",
            AuditAction.INTEGRATION_DISCONNECTED, id.toString(), "operator disconnected integration",
            integration.connectionState().name(), updated.connectionState().name(), Map.of()));
        return updated;
    }

    public void delete(IntegrationId id, OrganizationId callerOrganizationId) {
        Integration integration = get(id, callerOrganizationId);
        integrations.delete(id);
        auditTrail.append(AuditEvent.of(integration.organizationId().toString(), id.toString(), "user",
            AuditAction.INTEGRATION_DELETED, id.toString(), "operator deleted integration",
            integration.connectionState().name(), null, Map.of()));
    }

    private void validate(OrganizationId organizationId, CreateIntegrationCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw new ValidationException("INVALID_INTEGRATION", "name is required", Map.of("field", "name"));
        }
        if (!connectors.isImplemented(command.type())) {
            throw new ValidationException("CONNECTOR_NOT_IMPLEMENTED",
                "Connector type " + command.type() + " is not implemented by this build",
                Map.of("connectorType", command.type().name()));
        }
        Connector connector = connectors.connector(command.type());
        Set<IntegrationCredentialReference.CredentialKind> allowed = connector.supportedCredentialKinds();
        if (!allowed.contains(command.credential().kind())) {
            throw new ValidationException("INVALID_CREDENTIAL_REFERENCE",
                "Credential kind " + command.credential().kind() + " is not valid for " + command.type(),
                Map.of("connectorType", command.type().name(),
                    "credentialKind", command.credential().kind().name()));
        }
        if (command.credential().kind() == IntegrationCredentialReference.CredentialKind.AWS_ASSUME_ROLE
            && (command.credential().roleArn() == null || command.credential().roleArn().isBlank())) {
            throw new ValidationException("INVALID_CREDENTIAL_REFERENCE",
                "roleArn is required for AWS_ASSUME_ROLE", Map.of("field", "roleArn"));
        }
        if (requiresSecretReference(command.credential().kind())
            && (command.credential().secretReference() == null
                || command.credential().secretReference().isBlank())) {
            throw new ValidationException("INVALID_CREDENTIAL_REFERENCE",
                "secretReference is required for " + command.credential().kind(),
                Map.of("field", "secretReference"));
        }
        if (connector.requiresEndpoint()
            && (command.endpoint() == null || command.endpoint().isBlank())) {
            throw new ValidationException("INVALID_INTEGRATION",
                "endpoint is required for " + command.type(), Map.of("field", "endpoint"));
        }
        // Tenant scope always comes from the authenticated principal (§30).
        if (organizationId == null) {
            throw new ValidationException("INVALID_INTEGRATION", "organization is required", Map.of());
        }
    }

    private static boolean requiresSecretReference(IntegrationCredentialReference.CredentialKind kind) {
        return kind == IntegrationCredentialReference.CredentialKind.GITHUB_TOKEN
            || kind == IntegrationCredentialReference.CredentialKind.KUBERNETES_KUBECONFIG
            || kind == IntegrationCredentialReference.CredentialKind.POSTGRES_PASSWORD
            || kind == IntegrationCredentialReference.CredentialKind.GITHUB_APP;
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // Credential material must never reach an audit row or API response.
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
