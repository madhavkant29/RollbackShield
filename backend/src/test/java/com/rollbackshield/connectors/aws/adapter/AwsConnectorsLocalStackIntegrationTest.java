package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.LogEvidence;
import com.rollbackshield.shared.domain.OrganizationId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the AWS connectors that LocalStack community genuinely supports
 * (STS, SQS, CloudWatch Logs, EventBridge) through the real AWS SDK wire
 * protocol. ECS and ECR are pro-only in LocalStack; those adapters are
 * contract-tested in EcsRuntimeAdapterTest/EcrArtifactAdapterTest and
 * require the live-AWS run (Phase 9) for full verification -- this test does
 * not pretend otherwise.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsConnectorsLocalStackIntegrationTest {

    private static final String REGION = "us-east-1";

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:3.7.2")
        .withExposedPorts(4566)
        .withEnv("SERVICES", "sts,sqs,logs,events")
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));

    private static ConnectorContext context;

    @BeforeAll
    static void setUp() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        String endpoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566);
        Integration integration = Integration.pending(OrganizationId.newId(), "localstack",
            ConnectorType.AWS, REGION, IntegrationCredentialReference.awsControlPlaneRole(),
            Map.of("endpointOverride", endpoint));
        context = new ConnectorContext(integration, new CredentialMaterial.ControlPlaneRole());
    }

    @Test
    void stsConnectionTestSucceedsAndReportsTheRealAccount() {
        ConnectionTestResult result = new AwsConnector(new AwsClients()).testConnection(context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("sts:GetCallerIdentity ok");
        assertThat(result.details()).containsKey("account");
    }

    @Test
    void sqsDiscoveryFindsAQueueWithItsAttributes() {
        SqsClient sqs = client(SqsClient.class);
        sqs.createQueue(b -> b.queueName("payments-jobs"));

        List<DiscoveredResource> resources = new SqsQueueAdapter(new AwsClients()).discover(context);

        DiscoveredResource queue = resources.stream()
            .filter(resource -> resource.resourceType() == DiscoveredResourceType.QUEUE)
            .filter(resource -> resource.displayName().equals("payments-jobs"))
            .findFirst().orElseThrow(() -> new AssertionError("payments-jobs queue was not discovered"));
        assertThat(queue.metadata()).containsKey("queueUrl");
        assertThat(queue.metadata().get("approximateMessages")).isEqualTo("0");
    }

    @Test
    void cloudWatchLogsDiscoveryAndErrorEvidenceAreRealQueries() {
        CloudWatchLogsClient logs = client(CloudWatchLogsClient.class);
        logs.createLogGroup(b -> b.logGroupName("/rollbackshield/payments"));
        logs.createLogStream(b -> b.logGroupName("/rollbackshield/payments").logStreamName("checkout"));
        logs.putLogEvents(b -> b.logGroupName("/rollbackshield/payments").logStreamName("checkout")
            .logEvents(InputLogEvent.builder()
                .timestamp(Instant.now().minusSeconds(5).toEpochMilli())
                .message("ERROR checkout failed: upstream 500").build()));

        CloudWatchLogAdapter adapter = new CloudWatchLogAdapter(new AwsClients());
        List<DiscoveredResource> resources = adapter.discover(context);
        assertThat(resources).anyMatch(resource ->
            resource.resourceType() == DiscoveredResourceType.LOG_GROUP
                && resource.externalId().equals("/rollbackshield/payments"));

        List<LogEvidence> evidence = adapter.recentErrors(context, "/rollbackshield/payments",
            Instant.now().minusSeconds(60), 10);
        assertThat(evidence).isNotEmpty();
        assertThat(evidence.get(0).message()).contains("ERROR checkout failed");
    }

    @Test
    void eventBridgeDiscoveryFindsTheCreatedBus() {
        client(software.amazon.awssdk.services.eventbridge.EventBridgeClient.class)
            .createEventBus(b -> b.name("rollbackshield-events-it"));

        List<DiscoveredResource> resources = new EventBridgeDiscoveryAdapter(new AwsClients()).discover(context);

        assertThat(resources).anyMatch(resource ->
            resource.resourceType() == DiscoveredResourceType.EVENT_BUS
                && resource.externalId().equals("rollbackshield-events-it"));
    }

    private static <T> T client(Class<T> type) {
        String endpoint = context.config("endpointOverride", null);
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        if (type == SqsClient.class) {
            return type.cast(SqsClient.builder().endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(REGION)).credentialsProvider(credentials).build());
        }
        if (type == CloudWatchLogsClient.class) {
            return type.cast(CloudWatchLogsClient.builder().endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(REGION)).credentialsProvider(credentials).build());
        }
        if (type == StsClient.class) {
            return type.cast(StsClient.builder().endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(REGION)).credentialsProvider(credentials).build());
        }
        if (type == software.amazon.awssdk.services.eventbridge.EventBridgeClient.class) {
            return type.cast(software.amazon.awssdk.services.eventbridge.EventBridgeClient.builder()
                .endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(REGION)).credentialsProvider(credentials).build());
        }
        throw new IllegalArgumentException("Unsupported test client " + type);
    }
}
