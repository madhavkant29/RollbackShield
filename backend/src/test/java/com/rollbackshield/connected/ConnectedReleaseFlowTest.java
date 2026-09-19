package com.rollbackshield.connected;

import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.audit.adapter.InMemoryAuditTrail;
import com.rollbackshield.catalog.adapter.InMemoryAppServiceRepository;
import com.rollbackshield.catalog.adapter.InMemoryOrganizationRepository;
import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.connectors.flyway.adapter.FlywayMigrationAnalyzer;
import com.rollbackshield.contract.adapter.InMemoryRollbackContractRepository;
import com.rollbackshield.contract.application.ContractApplicationService;
import com.rollbackshield.contract.api.ContractDtos.RuleDto;
import com.rollbackshield.deployment.application.DeploymentObservationService;
import com.rollbackshield.integrations.adapter.InMemoryDeploymentObservationRepository;
import com.rollbackshield.integrations.adapter.InMemoryDiscoveredResourceRepository;
import com.rollbackshield.integrations.adapter.InMemoryIntegrationRepository;
import com.rollbackshield.integrations.adapter.InMemoryServiceMappingRepository;
import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.CreateIntegrationCommand;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.application.IntegrationSyncService;
import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.RollbackTarget;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.connector.ArtifactDescriptor;
import com.rollbackshield.integrations.domain.connector.ArtifactVerificationPort;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseMigrationAnalysisPort;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.HealthVerificationPort;
import com.rollbackshield.integrations.domain.connector.RepositoryInspection;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionPort;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionResult;
import com.rollbackshield.integrations.domain.connector.SourceMetadataPort;
import com.rollbackshield.release.adapter.InMemoryReleaseRepository;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.reversibility.application.ArtifactAvailabilityChecker;
import com.rollbackshield.reversibility.application.MigrationCompatibilityService;
import com.rollbackshield.reversibility.application.ReversibilityApplicationService;
import com.rollbackshield.reversibility.application.RuntimeCompatibilityService;
import com.rollbackshield.reversibility.domain.PreflightReport;
import com.rollbackshield.reversibility.domain.ReversibilityVerdict;
import com.rollbackshield.rollback.application.RollbackMonitorPolicy;
import com.rollbackshield.rollback.application.RollbackOrchestrator;
import com.rollbackshield.servicemapping.application.ServiceMappingApplicationService;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.events.adapter.LoggingEventPublisher;
import com.rollbackshield.workfence.application.WorkFenceApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The connected release path at the application layer, with no HTTP and no
 * real cloud: connect AWS + GitHub -> sync -> import a discovered ECS service
 * -> observe its deployment -> create a release from the observation ->
 * preflight (destructive migration blocks, safe migration allows) -> execute
 * a real rollback through the runtime connector. Every collaboration is a
 * repository or a connector port; the fake connectors are stand-ins for AWS
 * and GitHub, so this verifies orchestration logic exactly, and the live-AWS
 * run verifies the provider adapters themselves.
 */
class ConnectedReleaseFlowTest {

    private final InMemoryIntegrationRepository integrationRepository = new InMemoryIntegrationRepository();
    private final InMemoryDiscoveredResourceRepository discoveredResources =
        new InMemoryDiscoveredResourceRepository();
    private final InMemoryServiceMappingRepository mappings = new InMemoryServiceMappingRepository();
    private final InMemoryDeploymentObservationRepository observations =
        new InMemoryDeploymentObservationRepository();
    private final InMemoryReleaseRepository releases = new InMemoryReleaseRepository();
    private final InMemoryRollbackContractRepository contracts = new InMemoryRollbackContractRepository();
    private final InMemoryAuditTrail audit = new InMemoryAuditTrail();

    private final FakeAwsConnector aws = new FakeAwsConnector();
    private final FakeGitHubConnector github = new FakeGitHubConnector();
    private final FlywayMigrationAnalyzer flyway = new FlywayMigrationAnalyzer();
    private final WorkFenceApplicationService workFence = mock(WorkFenceApplicationService.class);
    private final CredentialResolver credentials = reference -> switch (reference.kind()) {
        case AWS_CONTROL_PLANE_ROLE -> new CredentialMaterial.ControlPlaneRole();
        case GITHUB_TOKEN -> new CredentialMaterial.GitHubToken("test-token");
        default -> new CredentialMaterial.None();
    };

    private CatalogApplicationService catalog;
    private IntegrationApplicationService integrationService;
    private ServiceMappingApplicationService mappingService;
    private DeploymentObservationService observationService;
    private ContractApplicationService contractService;
    private ReversibilityApplicationService preflight;
    private RollbackOrchestrator orchestrator;
    private OrganizationId organizationId;
    private ServiceId serviceId;
    private IntegrationId awsIntegrationId;
    private IntegrationId githubIntegrationId;

    @BeforeEach
    void setUp() {
        catalog = new CatalogApplicationService(new InMemoryOrganizationRepository(),
            new InMemoryAppServiceRepository());
        Organization organization = catalog.createOrganization("Acme");
        organizationId = organization.id();

        ConnectorRegistry registry = new ConnectorRegistry(List.of(aws, github, flyway),
            List.of(aws, github, flyway));
        LoggingEventPublisher events = new LoggingEventPublisher();
        IntegrationSyncService syncService = new IntegrationSyncService(integrationRepository,
            discoveredResources, registry, credentials, audit, events);
        integrationService = new IntegrationApplicationService(integrationRepository, registry, credentials,
            syncService, audit, events);
        ReleaseApplicationService releaseService = new ReleaseApplicationService(releases, audit,
            workFence, events);
        contractService = new ContractApplicationService(contracts, releaseService, releases, audit);
        mappingService = new ServiceMappingApplicationService(catalog, integrationService,
            integrationRepository, discoveredResources, mappings, registry, credentials, audit, events);
        ArtifactAvailabilityChecker artifactChecker = new ArtifactAvailabilityChecker(integrationService,
            registry, credentials);
        preflight = new ReversibilityApplicationService(releases, contracts, mappings,
            new RuntimeCompatibilityService(integrationService, registry, credentials, artifactChecker),
            new MigrationCompatibilityService(integrationService, registry, credentials, observations),
            integrationService, observations);
        observationService = new DeploymentObservationService(catalog, mappings, observations,
            integrationService, registry, credentials, releaseService, preflight, audit, events);
        orchestrator = new RollbackOrchestrator(releaseService, catalog, mappings, integrationService,
            registry, credentials, artifactChecker,
            new RollbackMonitorPolicy(Duration.ofSeconds(2), Duration.ofMillis(5)), audit, events);
    }

    @Test
    void connectsImportsObservesPreflightsAndRollsBackForReal() {
        connectAwsAndGitHub();
        importPaymentsService();

        ServiceMapping mapping = mappings.findByService(serviceId).orElseThrow();
        assertThat(mapping.bindingsFor(ResourceBinding.BindingRole.RUNTIME)).hasSize(1);
        assertThat(mapping.bindingsFor(ResourceBinding.BindingRole.ARTIFACT_REPOSITORY)).hasSize(1);
        assertThat(mapping.firstBinding(ResourceBinding.BindingRole.REPOSITORY).orElseThrow()
            .confidence()).isEqualTo(ResourceBinding.MappingConfidence.HIGH);

        // Previous deployment (baseline commit), then the candidate deployment.
        github.headCommitSha = "cafe0001";
        observationService.observe(organizationId, serviceId);
        Release first = observationService.createRelease(organizationId, serviceId).release();
        assertThat(first.candidateVersionLabel()).isEqualTo("payments@task-definition:2");
        assertThat(first.state()).isEqualTo(ReleaseState.READY);

        github.headCommitSha = "cafe0002";
        aws.candidateRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:3";
        aws.previousRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2";
        observationService.observe(organizationId, serviceId);
        Release candidate = observationService.createRelease(organizationId, serviceId).release();
        assertThat(candidate.id()).isNotEqualTo(first.id());
        assertThat(candidate.previousVersionLabel()).isEqualTo("payments@task-definition:2");
        // Idempotent: creating again returns the same release, not a second one.
        assertThat(observationService.createRelease(organizationId, serviceId).release().id())
            .isEqualTo(candidate.id());

        protect(candidate);

        // Destructive migration introduced by the candidate blocks rollback with evidence.
        github.changedMigrations = List.of("db/migration/V42__drop_billing.sql");
        github.migrationFiles = List.of(new MigrationFile("V42", "drop billing", 
            "db/migration/V42__drop_billing.sql",
            "ALTER TABLE customers DROP COLUMN billing_address;"));

        PreflightReport blocked = preflight.evaluate(candidate.id());
        assertThat(blocked.verdict()).isEqualTo(ReversibilityVerdict.CANNOT_ROLLBACK);
        assertThat(blocked.status().name()).isEqualTo("AT_RISK");
        assertThat(blocked.blockers()).anyMatch(blocker ->
            blocker.code().equals("DESTRUCTIVE_DATABASE_MIGRATION"));
        // The blocker names the migration, the operation with its line, and the rollback target.
        assertThat(blocked.blockers().stream()
            .filter(blocker -> blocker.code().equals("DESTRUCTIVE_DATABASE_MIGRATION"))
            .findFirst().orElseThrow().description())
            .contains("V42__drop_billing.sql")
            .contains("DROP COLUMN billing_address")
            .contains(candidate.previousVersionLabel());
        assertThat(blocked.evidence()).anyMatch(edge -> edge.relation().equals("destructive-change"));
        assertThat(blocked.evidence()).anyMatch(edge -> edge.relation().equals("exists-in"));
        // WHY NOT, as a structured path: the blocker carries its evidence chain.
        assertThat(blocked.blockerPaths()).anyMatch(path ->
            path.code().equals("DESTRUCTIVE_DATABASE_MIGRATION")
                && path.path().stream().anyMatch(edge -> edge.relation().equals("destructive-change"))
                && path.path().stream().anyMatch(edge -> edge.relation().equals("breaks")));

        // A candidate with no destructive migration is reversibly deployable.
        github.changedMigrations = List.of();
        mappingService.addBinding(organizationId, serviceId, ResourceBinding.BindingRole.QUEUE,
            awsIntegrationId, "https://sqs.us-east-1.amazonaws.com/000000000000/payments-jobs",
            "confirmed by operator");
        PreflightReport clear = preflight.evaluate(candidate.id());
        assertThat(clear.verdict()).isEqualTo(ReversibilityVerdict.CAN_ROLLBACK);
        assertThat(clear.status().name()).isEqualTo("REVERSIBLE");
        // The graph chains release -> commit -> runtime -> artifacts -> queue -> policy.
        assertThat(clear.evidence()).anyMatch(edge -> edge.relation().equals("source-commit"));
        assertThat(clear.evidence()).anyMatch(edge -> edge.relation().equals("mapped-runtime"));
        assertThat(clear.evidence()).anyMatch(edge -> edge.relation().equals("mapped-queue")
            && edge.object().contains("payments-jobs"));
        assertThat(clear.evidence()).anyMatch(edge -> edge.relation().equals("protected-by"));
        // No blockers -> no blocker paths.
        assertThat(clear.blockerPaths()).isEmpty();

        // Rollback executes against the runtime connector and converges.
        aws.rollbackRequestResult = RollbackExecutionResult.inProgress("ecs:UpdateService",
            "requested rollback to task-definition:1", Map.of());
        aws.rollbackMonitorResult = RollbackExecutionResult.completed("ecs:DescribeServices",
            "service is running task-definition:1", Map.of());
        aws.health = HealthObservation.healthy("running 1/1 tasks", Map.of("runningCount", "1"));

        Release rolledBack = orchestrator.rollback(organizationId, candidate.id(), "demo rollback");

        assertThat(rolledBack.state()).isEqualTo(ReleaseState.ROLLED_BACK);
        assertThat(aws.rollbackTargets).hasSize(1);
        assertThat(aws.rollbackTargets.get(0).targetRevision())
            .isEqualTo("arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2");
        verify(workFence).invalidateEpoch(eq(candidate.id()),
            eq(releases.findById(candidate.id()).orElseThrow().epoch()));
        assertThat(audit.listForRelease(candidate.id().toString()))
            .anyMatch(event -> event.action() == com.rollbackshield.audit.AuditAction.ROLLBACK_EXECUTION_STEP)
            .anyMatch(event -> event.action() == com.rollbackshield.audit.AuditAction.ROLLBACK_COMPLETED);
    }

    @Test
    void observingAutomaticallyCreatesAReadyReleaseAndRunsPreflight() {
        connectAwsAndGitHub();
        importPaymentsService();

        github.headCommitSha = "beef0001";
        DeploymentObservationService.ObservedDeployment first =
            observationService.observe(organizationId, serviceId);

        assertThat(first.releaseCreated()).isTrue();
        assertThat(first.release().state()).isEqualTo(ReleaseState.READY);
        assertThat(first.observation().releaseId()).isEqualTo(first.release().id());
        assertThat(first.release().candidateVersionLabel()).isEqualTo("payments@task-definition:2");
        assertThat(first.observation().identity().commitSha()).isEqualTo("beef0001");

        // Re-observing the same candidate revision must not spawn a second release.
        DeploymentObservationService.ObservedDeployment repeated =
            observationService.observe(organizationId, serviceId);
        assertThat(repeated.releaseCreated()).isFalse();
        assertThat(repeated.release().id()).isEqualTo(first.release().id());

        // A new runtime revision is a new release, linked to its own observation.
        aws.candidateRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:3";
        aws.previousRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2";
        github.headCommitSha = "beef0002";
        DeploymentObservationService.ObservedDeployment changed =
            observationService.observe(organizationId, serviceId);

        assertThat(changed.releaseCreated()).isTrue();
        assertThat(changed.release().id()).isNotEqualTo(first.release().id());
        assertThat(changed.release().previousVersionLabel()).isEqualTo("payments@task-definition:2");
        assertThat(changed.release().candidateVersionLabel()).isEqualTo("payments@task-definition:3");
        // The first preflight ran as part of observation and is in the audit trail.
        assertThat(audit.listForRelease(changed.release().id().toString()))
            .anyMatch(event -> event.action()
                == com.rollbackshield.audit.AuditAction.RELEASE_PREFLIGHT_EVALUATED)
            .anyMatch(event -> event.action() == com.rollbackshield.audit.AuditAction.RELEASE_OBSERVED);

        // A terminal (rolled-back) release must not be reused: re-observing the
        // same candidate opens a new release rather than reviving a closed one.
        protect(changed.release());
        orchestrator.rollback(organizationId, changed.release().id(), "close for regression");
        DeploymentObservationService.ObservedDeployment afterClose =
            observationService.observe(organizationId, serviceId);
        assertThat(afterClose.releaseCreated()).isTrue();
        assertThat(afterClose.release().id()).isNotEqualTo(changed.release().id());
    }

    @Test
    void ambiguousRepositoryMappingIsNotUsedForDecisionsUntilConfirmed() {
        connectAwsAndGitHub();
        importPaymentsService();

        // Downgrade the inferred repository binding to the ambiguous confidence
        // an operator sees when only the repository name matches.
        ServiceMapping mapping = mappings.findByService(serviceId).orElseThrow();
        ResourceBinding inferred = mapping.firstBinding(ResourceBinding.BindingRole.REPOSITORY).orElseThrow();
        ServiceMapping ambiguous = mapping.withBinding(new ResourceBinding(inferred.integrationId(),
            inferred.resourceType(), inferred.externalId(), inferred.role(),
            ResourceBinding.MappingConfidence.MEDIUM,
            "repository name matches service name; confirm this mapping", inferred.boundAt()));
        mappings.save(ambiguous);

        github.headCommitSha = "d00d0001";
        DeploymentObservationService.ObservedDeployment unconfirmed =
            observationService.observe(organizationId, serviceId);

        // Unconfirmed evidence is not trusted: no commit is recorded.
        assertThat(unconfirmed.observation().identity().commitSha()).isNull();

        // Operator confirmation upgrades the binding to HIGH and is audited...
        IntegrationId repositoryIntegration = mappings.findByService(serviceId).orElseThrow()
            .firstBinding(ResourceBinding.BindingRole.REPOSITORY).orElseThrow().integrationId();
        mappingService.addBinding(organizationId, serviceId, ResourceBinding.BindingRole.REPOSITORY,
            repositoryIntegration, "acme/payments", "confirmed by operator");
        assertThat(mappings.findByService(serviceId).orElseThrow()
            .firstBinding(ResourceBinding.BindingRole.REPOSITORY).orElseThrow().confidence())
            .isEqualTo(ResourceBinding.MappingConfidence.HIGH);
        assertThat(audit.listForRelease(serviceId.toString()))
            .anyMatch(event -> event.action()
                == com.rollbackshield.audit.AuditAction.SERVICE_MAPPING_CONFIRMED);

        // ...and the confirmed mapping is now used to record the running commit.
        DeploymentObservationService.ObservedDeployment confirmed =
            observationService.observe(organizationId, serviceId);
        assertThat(confirmed.observation().identity().commitSha()).isEqualTo("d00d0001");
    }

    @Test
    void missingRollbackArtifactBlocksRollbackAndNeverReportsRolledBack() {
        connectAwsAndGitHub();
        importPaymentsService();
        observationService.observe(organizationId, serviceId);
        Release release = observationService.createRelease(organizationId, serviceId).release();
        protect(release);

        aws.artifactAvailable = false;

        PreflightReport report = preflight.evaluate(release.id());
        assertThat(report.verdict()).isEqualTo(ReversibilityVerdict.CANNOT_ROLLBACK);
        assertThat(report.blockers()).anyMatch(blocker ->
            blocker.code().equals("ROLLBACK_ARTIFACT_MISSING"));

        Release failed = orchestrator.rollback(organizationId, release.id(), "artifact gone");

        assertThat(failed.state()).isEqualTo(ReleaseState.FAILED);
        assertThat(aws.rollbackTargets).isEmpty();
    }

    private void connectAwsAndGitHub() {
        Integration awsIntegration = integrationService.create(organizationId,
            new CreateIntegrationCommand("aws-hackathon", ConnectorType.AWS, "us-east-1",
                IntegrationCredentialReference.awsControlPlaneRole(), Map.of()));
        awsIntegrationId = awsIntegration.id();
        assertThat(integrationService.testConnection(awsIntegrationId, organizationId)
            .connectionState().name()).isEqualTo("CONNECTED");

        Integration githubIntegration = integrationService.create(organizationId,
            new CreateIntegrationCommand("github", ConnectorType.GITHUB, "https://api.github.com",
                IntegrationCredentialReference.githubToken("GITHUB_TOKEN"), Map.of()));
        githubIntegrationId = githubIntegration.id();
        assertThat(integrationService.testConnection(githubIntegrationId, organizationId)
            .connectionState().name()).isEqualTo("CONNECTED");

        assertThat(integrationService.sync(awsIntegrationId, organizationId).errorCount()).isZero();
        assertThat(integrationService.sync(githubIntegrationId, organizationId).errorCount()).isZero();
    }

    private void importPaymentsService() {
        ServiceMappingApplicationService.ImportedService imported = mappingService.importService(
            organizationId, awsIntegrationId, "rollbackshield-demo/payments", "payments");
        serviceId = imported.service().id();
    }

    private void protect(Release release) {
        Release current = releases.findById(release.id()).orElseThrow();
        if (current.state() == ReleaseState.DRAFT) {
            releaseService(current).prepare(release.id());
            current = releases.findById(release.id()).orElseThrow();
        }
        if (current.state() == ReleaseState.PREPARING) {
            releaseService(current).markReady(release.id());
        }
        contractService.createAndActivate(release.id(), Duration.ofHours(2), true,
            List.of(new RuleDto("ENUM_ALLOWED_VALUES", "Order", "status",
                List.of("CREATED", "PAID", "CANCELLED", "REFUNDED"), null, null, null, null)));
    }

    private ReleaseApplicationService releaseService(Release release) {
        return new ReleaseApplicationService(releases, audit, workFence, new LoggingEventPublisher());
    }

    /** Stand-in for the AWS connector family. */
    static final class FakeAwsConnector implements Connector, CapabilityProvider, DiscoveryContributionPort,
        DeploymentObservationPort, RollbackExecutionPort, HealthVerificationPort, ArtifactVerificationPort {

        boolean artifactAvailable = true;
        HealthObservation health = HealthObservation.healthy("running 1/1", Map.of());
        RollbackExecutionResult rollbackRequestResult = RollbackExecutionResult.inProgress("request",
            "requested", Map.of());
        RollbackExecutionResult rollbackMonitorResult = RollbackExecutionResult.completed("monitor",
            "completed", Map.of());
        String candidateRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2";
        String previousRevision = "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:1";
        final List<RollbackTarget> rollbackTargets = new java.util.ArrayList<>();

        @Override
        public ConnectorType type() {
            return ConnectorType.AWS;
        }

        @Override
        public java.util.Set<ConnectorCapability> capabilities() {
            return java.util.Set.of(ConnectorCapability.RUNTIME_DISCOVERY,
                ConnectorCapability.DEPLOYMENT_STATUS, ConnectorCapability.ROLLBACK_EXECUTION,
                ConnectorCapability.HEALTH_VERIFICATION, ConnectorCapability.ARTIFACT_DISCOVERY,
                ConnectorCapability.ARTIFACT_VERIFICATION);
        }        @Override
        public java.util.Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
            supportedCredentialKinds() {
            return java.util.Set.of(
                com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
                    .AWS_CONTROL_PLANE_ROLE,
                com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
                    .AWS_ASSUME_ROLE);
        }

        @Override
        public ConnectionTestResult testConnection(ConnectorContext context) {
            return ConnectionTestResult.ok("fake aws", Map.of());
        }

        @Override
        public List<DiscoveredResource> discover(ConnectorContext context) {
            DiscoveredResource runtime = DiscoveredResource.of(context.integration().id(),
                ConnectorType.AWS, DiscoveredResourceType.RUNTIME_SERVICE,
                "rollbackshield-demo/payments", "payments", "us-east-1",
                Map.of("image", "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments@sha256:candidate",
                    "taskDefinition", "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2"));
            DiscoveredResource queue = DiscoveredResource.of(context.integration().id(),
                ConnectorType.AWS, DiscoveredResourceType.QUEUE, "https://sqs.us-east-1.amazonaws.com/000000000000/payments-jobs", "payments-jobs", "us-east-1", Map.of("approximateMessages", "0"));
            DiscoveredResource artifactRepository = DiscoveredResource.of(context.integration().id(),
                ConnectorType.AWS, DiscoveredResourceType.ARTIFACT_REPOSITORY, "payments", "payments",
                "us-east-1", Map.of("repositoryUri",
                    "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments"));
            return List.of(runtime, artifactRepository, queue);
        }

        @Override
        public Optional<DeploymentIdentity> observeDeployment(ConnectorContext context,
                                                              String runtimeExternalId) {
            return Optional.of(new DeploymentIdentity("payments", runtimeExternalId,
                candidateRevision,
                previousRevision,
                "sha256:candidate", "sha256:previous", null, null,
                "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments", "ACTIVE"));
        }

        @Override
        public RollbackExecutionResult requestRollback(ConnectorContext context, RollbackTarget target) {
            rollbackTargets.add(target);
            return rollbackRequestResult;
        }

        @Override
        public RollbackExecutionResult monitorRollback(ConnectorContext context, RollbackTarget target) {
            return rollbackMonitorResult;
        }

        @Override
        public HealthObservation verifyHealth(ConnectorContext context, String runtimeExternalId) {
            return health;
        }

        @Override
        public Optional<ArtifactDescriptor> findArtifactByDigest(ConnectorContext context,
                                                                 String repositoryExternalId,
                                                                 String digest) {
            if (!artifactAvailable) {
                return Optional.empty();
            }
            return Optional.of(new ArtifactDescriptor(repositoryExternalId, digest, List.of("v41"),
                Instant.now(), 1L));
        }
    }

    /** Stand-in for the GitHub connector. */
    static final class FakeGitHubConnector implements Connector, CapabilityProvider,
        DiscoveryContributionPort, SourceMetadataPort {

        String headCommitSha = "cafe0001";
        List<String> changedMigrations = List.of();
        List<MigrationFile> migrationFiles = List.of();

        @Override
        public ConnectorType type() {
            return ConnectorType.GITHUB;
        }

        @Override
        public java.util.Set<ConnectorCapability> capabilities() {
            return java.util.Set.of(ConnectorCapability.SOURCE_DISCOVERY, ConnectorCapability.SOURCE_METADATA);
        }        @Override
        public java.util.Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
            supportedCredentialKinds() {
            return java.util.Set.of(
                com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_APP,
                com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_TOKEN,
                com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind.GITHUB_PUBLIC);
        }

        @Override
        public ConnectionTestResult testConnection(ConnectorContext context) {
            return ConnectionTestResult.ok("fake github", Map.of());
        }

        @Override
        public List<DiscoveredResource> discover(ConnectorContext context) {
            return List.of(DiscoveredResource.of(context.integration().id(), ConnectorType.GITHUB,
                DiscoveredResourceType.SOURCE_REPOSITORY, "acme/payments", "payments", null,
                Map.of("defaultBranch", "main")));
        }

        @Override
        public RepositoryInspection inspectSource(ConnectorContext context, String repositoryExternalId) {
            return new RepositoryInspection(repositoryExternalId, "main", headCommitSha, "deploy payments",
                Instant.now(), List.of("v42"), true, List.of("Dockerfile"),
                List.of("db/migration/V41__safe.sql"), List.of(), List.of(".github/workflows/deploy.yml"),
                List.of(headCommitSha));
        }

        @Override
        public List<MigrationFile> fetchMigrationFiles(ConnectorContext context,
                                                       String repositoryExternalId, List<String> paths) {
            return migrationFiles;
        }

        @Override
        public String fetchFile(ConnectorContext context, String repositoryExternalId, String path) {
            return "name: deploy\ndescription: deploy payments to ecs";
        }

        @Override
        public List<String> changedMigrationFilesBetween(ConnectorContext context,
                                                         String repositoryExternalId,
                                                         String baseSha, String headSha) {
            return changedMigrations;
        }
    }
}
