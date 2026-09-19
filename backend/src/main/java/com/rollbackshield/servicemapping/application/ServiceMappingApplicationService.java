package com.rollbackshield.servicemapping.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.RepositoryInspection;
import com.rollbackshield.integrations.domain.connector.SourceMetadataPort;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Imports discovered runtimes as RollbackShield services and builds their
 * ServiceMapping from real evidence: the runtime binding is the operator's
 * import action; the artifact binding comes from the image reference the
 * runtime actually reports; the repository binding is proven by a deployment
 * file that references the service, or explicitly marked MEDIUM for
 * confirmation. Queues/databases are only bound when a discovery observed
 * them or an operator confirms the edge -- never guessed.
 */
@Service
public class ServiceMappingApplicationService {

    private final CatalogApplicationService catalog;
    private final IntegrationApplicationService integrations;
    private final IntegrationRepository integrationRepository;
    private final DiscoveredResourceRepository discoveredResources;
    private final ServiceMappingRepository mappings;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final AuditTrail auditTrail;
    private final EventPublisher events;

    public ServiceMappingApplicationService(CatalogApplicationService catalog,
                                            IntegrationApplicationService integrations,
                                            IntegrationRepository integrationRepository,
                                            DiscoveredResourceRepository discoveredResources,
                                            ServiceMappingRepository mappings,
                                            ConnectorRegistry connectors,
                                            CredentialResolver credentials,
                                            AuditTrail auditTrail,
                                            EventPublisher events) {
        this.catalog = catalog;
        this.integrations = integrations;
        this.integrationRepository = integrationRepository;
        this.discoveredResources = discoveredResources;
        this.mappings = mappings;
        this.connectors = connectors;
        this.credentials = credentials;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    public ImportedService importService(OrganizationId organizationId, IntegrationId integrationId,
                                         String resourceExternalId, String requestedName) {
        Integration integration = integrations.get(integrationId, organizationId);
        DiscoveredResource runtime = findRuntimeResource(integration, resourceExternalId);
        String name = requestedName != null && !requestedName.isBlank()
            ? requestedName : runtime.displayName();
        AppService service = catalog.createService(organizationId, name);

        ServiceMapping mapping = ServiceMapping.empty(organizationId, service.id())
            .withBinding(runtimeBinding(integration, runtime));
        mapping = inferArtifactRepository(mapping, integration, runtime, organizationId);
        mapping = inferSourceRepository(mapping, integration, runtime, service);
        mapping = mappings.save(mapping);

        auditTrail.append(AuditEvent.of(organizationId.toString(), service.id().toString(), "user",
            AuditAction.SERVICE_IMPORTED, service.id().toString(),
            "imported " + runtime.displayName() + " from " + integration.name(),
            null, null, Map.of("integrationId", integration.id().toString(),
                "resourceType", runtime.resourceType().name())));
        events.publish(DomainEvent.of("ServiceImported", organizationId.toString(),
            service.id().toString(), Map.of("integrationId", integration.id().toString(),
                "connectorType", integration.type().name())));
        return new ImportedService(service, mapping);
    }

    public ServiceMapping getOrEmpty(OrganizationId organizationId, ServiceId serviceId) {
        catalog.getService(serviceId, organizationId);
        return mappings.findByService(serviceId)
            .orElseGet(() -> ServiceMapping.empty(organizationId, serviceId));
    }

    public ServiceMapping addBinding(OrganizationId organizationId, ServiceId serviceId,
                                     ResourceBinding.BindingRole role, IntegrationId integrationId,
                                     String externalId, String evidence) {
        catalog.getService(serviceId, organizationId);
        Integration integration = integrations.get(integrationId, organizationId);
        discoveredResources.findByExternalId(integrationId, resourceTypeFor(role), externalId)
            .orElseThrow(() -> new NotFoundException("RESOURCE_NOT_FOUND",
                "No discovered " + resourceTypeFor(role) + " " + externalId + " in " + integration.name()));
        ServiceMapping mapping = mappings.findByService(serviceId)
            .orElseGet(() -> ServiceMapping.empty(organizationId, serviceId));
        boolean alreadyBound = mapping.bindings().stream().anyMatch(existing ->
            existing.role() == role && existing.integrationId().equals(integrationId)
                && existing.externalId().equals(externalId));
        ResourceBinding binding = new ResourceBinding(integrationId, resourceTypeFor(role), externalId, role,
            ResourceBinding.MappingConfidence.HIGH,
            evidence == null || evidence.isBlank()
                ? (alreadyBound ? "confirmed by operator" : "confirmed by operator")
                : evidence,
            Instant.now());
        ServiceMapping updated = mappings.save(mapping.withBinding(binding));
        auditTrail.append(AuditEvent.of(organizationId.toString(), serviceId.toString(), "user",
            alreadyBound ? AuditAction.SERVICE_MAPPING_CONFIRMED : AuditAction.SERVICE_MAPPING_UPDATED,
            serviceId.toString(),
            (alreadyBound ? "confirmed " : "bound ") + role + " " + externalId,
            null, null, Map.of("integrationId", integrationId.toString(),
                "confirmed", String.valueOf(alreadyBound))));
        return updated;
    }

    public ServiceMapping removeBinding(OrganizationId organizationId, ServiceId serviceId,
                                        ResourceBinding.BindingRole role, String externalId) {
        catalog.getService(serviceId, organizationId);
        ServiceMapping mapping = mappings.findByService(serviceId)
            .orElseGet(() -> ServiceMapping.empty(organizationId, serviceId));
        ServiceMapping updated = mappings.save(mapping.withoutBinding(role, externalId));
        auditTrail.append(AuditEvent.of(organizationId.toString(), serviceId.toString(), "user",
            AuditAction.SERVICE_MAPPING_UPDATED, serviceId.toString(),
            "unbound " + role + " " + externalId, null, null, Map.of()));
        return updated;
    }

    private DiscoveredResource findRuntimeResource(Integration integration, String resourceExternalId) {
        for (DiscoveredResourceType type : List.of(DiscoveredResourceType.RUNTIME_SERVICE,
            DiscoveredResourceType.KUBERNETES_DEPLOYMENT)) {
            Optional<DiscoveredResource> resource = discoveredResources.findByExternalId(
                integration.id(), type, resourceExternalId);
            if (resource.isPresent()) {
                return resource.get();
            }
        }
        throw new NotFoundException("RESOURCE_NOT_FOUND",
            "No discovered runtime " + resourceExternalId + " in " + integration.name()
                + "; sync the integration first");
    }

    private ResourceBinding runtimeBinding(Integration integration, DiscoveredResource runtime) {
        return new ResourceBinding(integration.id(), runtime.resourceType(), runtime.externalId(),
            ResourceBinding.BindingRole.RUNTIME, ResourceBinding.MappingConfidence.HIGH,
            "imported by operator from " + integration.type() + " " + runtime.resourceType(), Instant.now());
    }

    private ServiceMapping inferArtifactRepository(ServiceMapping mapping, Integration runtimeIntegration,
                                                   DiscoveredResource runtime,
                                                   OrganizationId organizationId) {
        String image = runtime.metadata().get("image");
        String repository = DeploymentIdentity.artifactRepository(image);
        if (repository == null || repository.isBlank()) {
            return mapping;
        }
        String repositoryName = repository.contains("/")
            ? repository.substring(repository.lastIndexOf('/') + 1) : repository;

        // The image URI is hard evidence of the artifact repository; find the
        // discovery that observed it (same integration first, then any of the
        // organization's integrations).
        Optional<DiscoveredResource> artifactRepository = discoveredResources.findByExternalId(
                runtimeIntegration.id(), DiscoveredResourceType.ARTIFACT_REPOSITORY, repositoryName)
            .or(() -> discoveredResources.findByExternalId(
                runtimeIntegration.id(), DiscoveredResourceType.ARTIFACT_REPOSITORY, repository));
        if (artifactRepository.isEmpty()) {
            artifactRepository = integrationRepository.findByOrganization(organizationId).stream()
                .filter(integration -> connectors.supportedCapabilities(integration.type())
                    .contains(ConnectorCapability.ARTIFACT_DISCOVERY))
                .map(integration -> discoveredResources.findByExternalId(integration.id(),
                    DiscoveredResourceType.ARTIFACT_REPOSITORY, repositoryName))
                .flatMap(Optional::stream)
                .findFirst();
        }
        if (artifactRepository.isEmpty()) {
            return mapping;
        }
        DiscoveredResource repositoryResource = artifactRepository.get();
        ResourceBinding binding = new ResourceBinding(repositoryResource.integrationId(),
            DiscoveredResourceType.ARTIFACT_REPOSITORY, repositoryResource.externalId(),
            ResourceBinding.BindingRole.ARTIFACT_REPOSITORY, ResourceBinding.MappingConfidence.HIGH,
            "container image " + image + " references this repository", Instant.now());
        return mapping.withBinding(binding);
    }

    private ServiceMapping inferSourceRepository(ServiceMapping mapping, Integration runtimeIntegration,
                                                 DiscoveredResource runtime, AppService service) {
        for (Integration integration : integrationRepository.findByOrganization(service.organizationId())) {
            if (!connectors.supportedCapabilities(integration.type())
                .contains(ConnectorCapability.SOURCE_DISCOVERY)) {
                continue;
            }
            List<DiscoveredResource> repositories = discoveredResources.findByIntegrationAndType(
                integration.id(), DiscoveredResourceType.SOURCE_REPOSITORY);
            Optional<DiscoveredResource> match = repositories.stream()
                .filter(candidate -> candidate.displayName().equalsIgnoreCase(service.name())
                    || candidate.externalId().endsWith("/" + service.name()))
                .findFirst();
            if (match.isEmpty()) {
                continue;
            }
            DiscoveredResource repository = match.get();
            RepositoryInspection inspection = inspectRepository(integration, repository);
            Provenance provenance = provenanceFor(integration, repository, runtime, inspection);
            ServiceMapping updated = mapping.withBinding(new ResourceBinding(integration.id(),
                DiscoveredResourceType.SOURCE_REPOSITORY, repository.externalId(),
                ResourceBinding.BindingRole.REPOSITORY, provenance.confidence(), provenance.evidence(),
                Instant.now()));
            if (inspection != null && inspection.hasAnyMigrationSource()) {
                StringBuilder evidence = new StringBuilder("repository contains ");
                boolean wroteAny = false;
                if (inspection.hasMigrationFiles()) {
                    evidence.append(inspection.migrationFilePaths().size())
                        .append(" Flyway migration files");
                    wroteAny = true;
                }
                if (inspection.hasLiquibaseChangelogs()) {
                    if (wroteAny) {
                        evidence.append(" and ");
                    }
                    evidence.append(inspection.liquibaseChangelogPaths().size())
                        .append(" Liquibase changelog files");
                }
                evidence.append(" (observed via source connector)");
                updated = updated.withBinding(new ResourceBinding(integration.id(),
                    DiscoveredResourceType.SOURCE_REPOSITORY, repository.externalId(),
                    ResourceBinding.BindingRole.MIGRATION_SOURCE,
                    ResourceBinding.MappingConfidence.HIGH, evidence.toString(), Instant.now()));
            }
            return updated;
        }
        return mapping;
    }

    private RepositoryInspection inspectRepository(Integration integration, DiscoveredResource repository) {
        try {
            SourceMetadataPort sourcePort = connectors.port(integration.type(),
                ConnectorCapability.SOURCE_METADATA, SourceMetadataPort.class);
            return sourcePort.inspectSource(new ConnectorContext(integration,
                credentials.resolve(integration.credential())), repository.externalId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Provenance provenanceFor(Integration integration, DiscoveredResource repository,
                                     DiscoveredResource runtime, RepositoryInspection inspection) {
        if (inspection == null) {
            return new Provenance(ResourceBinding.MappingConfidence.MEDIUM,
                "repository name matches service name; repository could not be inspected");
        }
        try {
            SourceMetadataPort sourcePort = connectors.port(integration.type(),
                ConnectorCapability.SOURCE_METADATA, SourceMetadataPort.class);
            ConnectorContext context = new ConnectorContext(integration,
                credentials.resolve(integration.credential()));
            for (String path : inspection.deploymentFilePaths().stream().limit(3).toList()) {
                String content = sourcePort.fetchFile(context, repository.externalId(), path);
                if (content != null && (content.contains(runtime.displayName())
                    || content.contains(repository.displayName()))) {
                    return new Provenance(ResourceBinding.MappingConfidence.HIGH,
                        "deployment file " + path + " references " + runtime.displayName());
                }
            }
            return new Provenance(ResourceBinding.MappingConfidence.MEDIUM,
                "repository name matches service name; confirm this mapping");
        } catch (RuntimeException e) {
            return new Provenance(ResourceBinding.MappingConfidence.MEDIUM,
                "repository name matches service name; deployment files could not be inspected: "
                    + e.getMessage());
        }
    }

    private static DiscoveredResourceType resourceTypeFor(ResourceBinding.BindingRole role) {
        return switch (role) {
            case REPOSITORY -> DiscoveredResourceType.SOURCE_REPOSITORY;
            case RUNTIME -> DiscoveredResourceType.RUNTIME_SERVICE;
            case ARTIFACT_REPOSITORY -> DiscoveredResourceType.ARTIFACT_REPOSITORY;
            case QUEUE -> DiscoveredResourceType.QUEUE;
            case EVENT_BUS -> DiscoveredResourceType.EVENT_BUS;
            case DATABASE -> DiscoveredResourceType.DATABASE;
            case MIGRATION_SOURCE -> DiscoveredResourceType.SOURCE_REPOSITORY;
        };
    }

    /** Result of an import; the caller gets both the service and its mapping. */
    public record ImportedService(AppService service, ServiceMapping mapping) {
    }

    private record Provenance(ResourceBinding.MappingConfidence confidence, String evidence) {
    }
}
