package com.rollbackshield.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.catalog.adapter.DynamoDbAppServiceRepository;
import com.rollbackshield.catalog.adapter.DynamoDbOrganizationRepository;
import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.integrations.adapter.DynamoDbDeploymentObservationRepository;
import com.rollbackshield.release.adapter.DynamoDbReleaseRepository;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.integrations.adapter.DynamoDbDiscoveredResourceRepository;
import com.rollbackshield.integrations.adapter.DynamoDbIntegrationRepository;
import com.rollbackshield.integrations.adapter.DynamoDbServiceMappingRepository;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the connectivity DynamoDB adapters against DynamoDB Local with the
 * exact key schema/gsi1 from `infrastructure/lib/data-stack.ts`. This is
 * what makes the 'aws' profile deployable: item classes meet a real
 * DynamoDB, and replacement/deletion semantics for discovered resources are
 * exercised rather than assumed.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbConnectivityAdapterIntegrationTest {

    private static final String TABLE = "rollbackshield";

    @Container
    static final GenericContainer<?> DYNAMODB =
        new GenericContainer<>("amazon/dynamodb-local:2.5.2").withExposedPorts(8000);

    private static DynamoDbIntegrationRepository integrations;
    private static DynamoDbDiscoveredResourceRepository resources;
    private static DynamoDbServiceMappingRepository mappings;
    private static DynamoDbDeploymentObservationRepository observations;
    private static DynamoDbOrganizationRepository organizationsRepo;
    private static DynamoDbAppServiceRepository servicesRepo;
    private static DynamoDbReleaseRepository releasesRepo;

    @BeforeAll
    static void setUp() {
        DynamoDbClient client = DynamoDbClient.builder()
            .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000)))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build();
        client.createTable(CreateTableRequest.builder()
            .tableName(TABLE)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1sk").attributeType(ScalarAttributeType.S).build())
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName("gsi1")
                .keySchema(
                    KeySchemaElement.builder().attributeName("gsi1pk").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("gsi1sk").keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build())
            .build());

        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        integrations = new DynamoDbIntegrationRepository(enhanced, TABLE, mapper);
        resources = new DynamoDbDiscoveredResourceRepository(enhanced, TABLE, mapper);
        mappings = new DynamoDbServiceMappingRepository(enhanced, TABLE, mapper);
        observations = new DynamoDbDeploymentObservationRepository(enhanced, TABLE);
        organizationsRepo = new DynamoDbOrganizationRepository(enhanced, TABLE);
        servicesRepo = new DynamoDbAppServiceRepository(enhanced, TABLE);
        releasesRepo = new DynamoDbReleaseRepository(enhanced, TABLE);
    }

    /**
     * Regression for the deployed-plane 500s: every org-scoped item type
     * shares gsi1pk=ORG#&lt;org&gt;. Without a sort-prefix each repository
     * deserialized foreign items into its own bean and NPE'd on null fields
     * (ServiceId.of(null), CredentialKind.valueOf(null)). Each query must
     * return only its own entity type.
     */
    @Test
    void organizationScopedIndexQueriesReturnOnlyTheirOwnItemType() {
        OrganizationId org = OrganizationId.newId();
        organizationsRepo.save(new Organization(org, "Acme", Instant.now()));
        ServiceId serviceId = ServiceId.newId();
        servicesRepo.save(new AppService(serviceId, org, "payments", Instant.now()));

        Integration integration = Integration.pending(org, "aws-live", ConnectorType.AWS,
            "ap-south-1", IntegrationCredentialReference.awsControlPlaneRole(), Map.of());
        integrations.save(integration);
        mappings.save(ServiceMapping.empty(org, serviceId));

        assertThat(servicesRepo.findByOrganization(org))
            .extracting(AppService::id).containsExactly(serviceId);
        assertThat(integrations.findByOrganization(org))
            .extracting(Integration::id).containsExactly(integration.id());
        assertThat(mappings.findByOrganization(org))
            .extracting(ServiceMapping::serviceId).containsExactly(serviceId);
    }

    @Test
    void integrationRoundTripsIncludingConnectionAndSyncState() {
        OrganizationId org = OrganizationId.newId();
        Integration integration = Integration.pending(org, "aws-hackathon", ConnectorType.AWS,
            "us-east-1", IntegrationCredentialReference.awsControlPlaneRole(),
            Map.of("region", "us-east-1", "expectedAccountId", "111122223333"));
        Integration connected = integration
            .withConnectionResult(com.rollbackshield.integrations.domain.ConnectionTestResult.ok(
                "sts:GetCallerIdentity ok", Map.of("account", "111122223333")))
            .recordSyncAttempt()
            .recordSyncSuccess(new com.rollbackshield.integrations.domain.ConnectorHealth(
                com.rollbackshield.integrations.domain.ConnectorHealth.HealthState.HEALTHY,
                "discovered 3 resources", Instant.now()), 3);
        integrations.save(connected);

        Integration loaded = integrations.findById(connected.id()).orElseThrow();
        assertThat(loaded).isEqualTo(connected);
        assertThat(loaded.connectionState().name()).isEqualTo("CONNECTED");
        assertThat(loaded.syncState().lastDiscoveredCount()).isEqualTo(3);
        assertThat(loaded.credential().roleArn()).isNull();

        assertThat(integrations.findByOrganization(org))
            .extracting(Integration::id)
            .containsExactly(connected.id());

        integrations.delete(connected.id());
        assertThat(integrations.findById(connected.id())).isEmpty();
    }

    @Test
    void discoveredResourcesAreReplacedByTypeAndLookedUpByExternalId() {
        IntegrationId integrationId = IntegrationId.newId();
        DiscoveredResource queue = DiscoveredResource.of(integrationId, ConnectorType.AWS,
            DiscoveredResourceType.QUEUE, "https://sqs/payments-jobs", "payments-jobs",
            "us-east-1", Map.of("approximateMessages", "0"));
        DiscoveredResource repository = DiscoveredResource.of(integrationId, ConnectorType.AWS,
            DiscoveredResourceType.ARTIFACT_REPOSITORY, "payments", "payments", "us-east-1",
            Map.of("repositoryUri", "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments"));
        DiscoveredResource stale = DiscoveredResource.of(integrationId, ConnectorType.AWS,
            DiscoveredResourceType.QUEUE, "https://sqs/legacy", "legacy", "us-east-1", Map.of());

        resources.replaceForIntegration(integrationId, List.of(queue, repository, stale));
        // A later sync no longer sees the legacy queue: it must disappear.
        resources.replaceForIntegration(integrationId, List.of(queue, repository));

        assertThat(resources.findByIntegration(integrationId)).hasSize(2);
        assertThat(resources.findByIntegrationAndType(integrationId, DiscoveredResourceType.QUEUE))
            .extracting(DiscoveredResource::externalId)
            .containsExactly("https://sqs/payments-jobs");
        assertThat(resources.findByExternalId(integrationId, DiscoveredResourceType.ARTIFACT_REPOSITORY,
            "payments")).isPresent();
        assertThat(resources.findByExternalId(integrationId, DiscoveredResourceType.QUEUE, "https://sqs/legacy"))
            .isEmpty();
    }

    @Test
    void serviceMappingAndLatestObservationRoundTrip() {
        OrganizationId org = OrganizationId.newId();
        ServiceId serviceId = ServiceId.newId();
        ServiceMapping mapping = ServiceMapping.empty(org, serviceId).withBinding(new ResourceBinding(
            IntegrationId.newId(), DiscoveredResourceType.RUNTIME_SERVICE,
            "cluster/payments", ResourceBinding.BindingRole.RUNTIME,
            ResourceBinding.MappingConfidence.HIGH, "imported by operator", Instant.now()));
        mappings.save(mapping);

        ServiceMapping loaded = mappings.findByService(serviceId).orElseThrow();
        assertThat(loaded.bindings()).hasSize(1);
        assertThat(loaded.bindings().get(0).externalId()).isEqualTo("cluster/payments");
        assertThat(mappings.findByOrganization(org)).hasSize(1);

        DeploymentObservation older = DeploymentObservation.observe(org, serviceId, IntegrationId.newId(),
            identity("arn:aws:ecs:us-east-1:111122223333:task-definition/payments:1",
                "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2"));
        observations.save(older);
        // A Release shares gsi1pk=SERVICE#<id>; the observation query must
        // not map it (regression: runtimeName NPE on the deployed plane).
        releasesRepo.save(Release.draft(org, serviceId, "v1", "v2"));
        DeploymentObservation newer = DeploymentObservation.observe(org, serviceId, IntegrationId.newId(),
            identity("arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2",
                "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:3"));
        observations.save(newer);

        assertThat(observations.findById(newer.id())).isPresent();
        assertThat(observations.findLatestForService(serviceId))
            .map(observation -> observation.identity().candidateRevision())
            .contains("arn:aws:ecs:us-east-1:111122223333:task-definition/payments:3");
        assertThat(observations.findByService(serviceId)).hasSize(2);
    }

    private static DeploymentIdentity identity(String previous, String candidate) {
        return new DeploymentIdentity("payments", "cluster/payments", candidate, previous,
            "sha256:candidate", "sha256:previous", "abc1234", "main",
            "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments", "ACTIVE");
    }
}
