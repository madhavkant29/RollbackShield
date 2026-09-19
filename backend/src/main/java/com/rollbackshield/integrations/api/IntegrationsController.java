package com.rollbackshield.integrations.api;

import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.CreateIntegrationCommand;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.SyncResult;
import com.rollbackshield.shared.api.ValidationException;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rollbackshield.integrations.api.IntegrationDtos.*;

/**
 * Integration management API. Tenant scope always comes from the
 * authenticated principal; no integration id or organization id is ever
 * trusted from path/body (§30). Credentials are accepted as references
 * only -- no secret value is read from or written to this API.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationsController {

    private final IntegrationApplicationService integrations;
    private final DiscoveredResourceRepository resources;
    private final ConnectorRegistry connectors;

    public IntegrationsController(IntegrationApplicationService integrations,
                                  DiscoveredResourceRepository resources,
                                  ConnectorRegistry connectors) {
        this.integrations = integrations;
        this.resources = resources;
        this.connectors = connectors;
    }

    @PostMapping
    public ResponseEntity<IntegrationResponse> create(@Valid @RequestBody CreateIntegrationRequest request) {
        OrganizationId organizationId = OrganizationId.of(CurrentPrincipal.get().organizationId());
        ConnectorType type = ConnectorType.parse(request.type())
            .orElseThrow(() -> new ValidationException("CONNECTOR_NOT_IMPLEMENTED",
                "Unknown connector type " + request.type(),
                Map.of("connectorType", String.valueOf(request.type()))));
        IntegrationCredentialReference credential = toDomain(request.credential());
        Integration integration = integrations.create(organizationId,
            new CreateIntegrationCommand(request.name(), type, request.endpoint(), credential,
                request.configuration() == null ? Map.of() : request.configuration()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(integration));
    }

    @GetMapping
    public List<IntegrationResponse> list() {
        OrganizationId organizationId = OrganizationId.of(CurrentPrincipal.get().organizationId());
        return integrations.list(organizationId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{integrationId}")
    public IntegrationResponse get(@PathVariable String integrationId) {
        return toResponse(getOwned(integrationId));
    }

    @PostMapping("/{integrationId}/test")
    public ConnectionTestResponse test(@PathVariable String integrationId) {
        Integration integration = integrations.testConnection(IntegrationId.of(integrationId), callerOrg());
        return new ConnectionTestResponse(integration.connectionState().name().equals("CONNECTED"),
            integration.health().detail(), String.valueOf(integration.health().checkedAt()),
            Map.of("connectionState", integration.connectionState().name(),
                "healthState", integration.health().state().name()));
    }

    @PostMapping("/{integrationId}/sync")
    public SyncResultResponse sync(@PathVariable String integrationId) {
        SyncResult result = integrations.sync(IntegrationId.of(integrationId), callerOrg());
        return new SyncResultResponse(result.integrationId().toString(), result.discoveredCount(),
            result.removedCount(), result.errorCount(), result.errors(), result.completedAt().toString());
    }

    @PostMapping("/{integrationId}/disconnect")
    public IntegrationResponse disconnect(@PathVariable String integrationId) {
        return toResponse(integrations.disconnect(IntegrationId.of(integrationId), callerOrg()));
    }

    @DeleteMapping("/{integrationId}")
    public ResponseEntity<Void> delete(@PathVariable String integrationId) {
        integrations.delete(IntegrationId.of(integrationId), callerOrg());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{integrationId}/resources")
    public List<DiscoveredResourceResponse> listResources(@PathVariable String integrationId,
                                                          @RequestParam(required = false) String type) {
        Integration integration = getOwned(integrationId);
        List<DiscoveredResource> found = type == null || type.isBlank()
            ? resources.findByIntegration(integration.id())
            : resources.findByIntegrationAndType(integration.id(), parseResourceType(type));
        return found.stream().map(IntegrationsController::toResponse).toList();
    }

    /** Runtimes that can be imported as RollbackShield services. */
    @GetMapping("/{integrationId}/services")
    public List<DiscoveredResourceResponse> listImportableServices(@PathVariable String integrationId) {
        Integration integration = getOwned(integrationId);
        List<DiscoveredResource> found = new java.util.ArrayList<>();
        found.addAll(resources.findByIntegrationAndType(integration.id(),
            DiscoveredResourceType.RUNTIME_SERVICE));
        found.addAll(resources.findByIntegrationAndType(integration.id(),
            DiscoveredResourceType.KUBERNETES_DEPLOYMENT));
        return found.stream().map(IntegrationsController::toResponse).toList();
    }

    private Integration getOwned(String integrationId) {
        return integrations.get(IntegrationId.of(integrationId), callerOrg());
    }

    private static OrganizationId callerOrg() {
        return OrganizationId.of(CurrentPrincipal.get().organizationId());
    }

    private static DiscoveredResourceType parseResourceType(String value) {
        try {
            return DiscoveredResourceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_RESOURCE_TYPE", "Unknown resource type " + value,
                Map.of("resourceType", value));
        }
    }

    private static IntegrationCredentialReference toDomain(CredentialReferenceDto dto) {
        IntegrationCredentialReference.CredentialKind kind;
        try {
            kind = IntegrationCredentialReference.CredentialKind.valueOf(dto.kind().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_CREDENTIAL_REFERENCE",
                "Unknown credential kind " + dto.kind(), Map.of("credentialKind", dto.kind()));
        }
        return new IntegrationCredentialReference(kind, dto.secretReference(), dto.roleArn(), dto.externalId());
    }

    private IntegrationResponse toResponse(Integration integration) {
        return new IntegrationResponse(
            integration.id().toString(),
            integration.organizationId().toString(),
            integration.name(),
            integration.type().name(),
            integration.type().category().name(),
            integration.endpoint(),
            integration.connectionState().name(),
            integration.health().state().name(),
            integration.health().detail(),
            Optional.ofNullable(integration.lastAttemptedSyncAt()).map(Object::toString).orElse(null),
            Optional.ofNullable(integration.lastSuccessfulSyncAt()).map(Object::toString).orElse(null),
            integration.lastError(),
            connectors.supportedCapabilities(integration.type()).stream().map(Enum::name).sorted().toList(),
            integration.configuration(),
            resources.findByIntegration(integration.id()).size(),
            String.valueOf(integration.createdAt()));
    }

    private static DiscoveredResourceResponse toResponse(DiscoveredResource resource) {
        return new DiscoveredResourceResponse(resource.resourceId(), resource.resourceType().name(),
            resource.externalId(), resource.displayName(), resource.region(), resource.metadata(),
            resource.discoveredAt().toString());
    }
}
