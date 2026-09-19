package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.RollbackTarget;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionResult;
import com.rollbackshield.shared.domain.OrganizationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersResponse;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;
import software.amazon.awssdk.services.ecs.paginators.ListClustersIterable;
import software.amazon.awssdk.services.ecs.paginators.ListServicesIterable;
import software.amazon.awssdk.services.ecs.paginators.ListTaskDefinitionsIterable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the ECS adapter's translation of real ECS API responses into
 * deployment identities and rollback calls. The SDK client is a collaborator
 * here, not the unit under test: ECS itself cannot be emulated by LocalStack
 * community, so this suite is a contract test that must be backed by the
 * live-AWS verification described in docs/operations/AWS_DEPLOYMENT.md.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EcsRuntimeAdapterTest {

    private static final String CLUSTER = "arn:aws:ecs:us-east-1:111122223333:cluster/rollbackshield-demo";
    private static final String TASK_DEF_V1 =
        "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:1";
    private static final String TASK_DEF_V2 =
        "arn:aws:ecs:us-east-1:111122223333:task-definition/payments:2";

    @Mock
    private AwsClients clients;
    @Mock
    private EcsClient ecs;

    private EcsRuntimeAdapter adapter;
    private ConnectorContext context;

    @BeforeEach
    void setUp() {
        adapter = new EcsRuntimeAdapter(clients);
        when(clients.ecs(any())).thenReturn(ecs);
        when(clients.region(any())).thenReturn(Region.US_EAST_1);
        Integration integration = Integration.pending(OrganizationId.newId(), "hackathon",
            ConnectorType.AWS, "us-east-1",
            IntegrationCredentialReference.awsControlPlaneRole(), Map.of());
        context = new ConnectorContext(integration, new CredentialMaterial.ControlPlaneRole());
    }

    @Test
    void discoveryListsClustersAndServicesWithRealMetadata() {
        ListClustersIterable clusters = mock(ListClustersIterable.class);
        when(clusters.clusterArns()).thenReturn(sdkIterable(CLUSTER));
        when(ecs.listClustersPaginator()).thenReturn(clusters);
        when(ecs.describeClusters(any(DescribeClustersRequest.class)))
            .thenReturn(DescribeClustersResponse.builder().clusters(Cluster.builder()
                .clusterArn(CLUSTER).clusterName("rollbackshield-demo").status("ACTIVE")
                .activeServicesCount(1).runningTasksCount(2).build()).build());

        ListServicesIterable services = mock(ListServicesIterable.class);
        when(services.serviceArns()).thenReturn(sdkIterable(CLUSTER + "/payments"));
        when(ecs.listServicesPaginator(any(ListServicesRequest.class))).thenReturn(services);
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service(TASK_DEF_V2, deployments(TASK_DEF_V2, "COMPLETED"), 1, 1)).build());
        when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
            .thenReturn(taskDefinition("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments@sha256:abc"));

        List<DiscoveredResource> resources = adapter.discover(context);

        assertTrue(resources.stream().anyMatch(resource ->
            resource.resourceType() == DiscoveredResourceType.RUNTIME_CLUSTER
                && resource.externalId().equals(CLUSTER)));
        DiscoveredResource service = resources.stream()
            .filter(resource -> resource.resourceType() == DiscoveredResourceType.RUNTIME_SERVICE)
            .findFirst().orElseThrow();
        assertEquals("rollbackshield-demo/payments", service.externalId());
        assertEquals(TASK_DEF_V2, service.metadata().get("taskDefinition"));
        assertEquals("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments@sha256:abc",
            service.metadata().get("image"));
    }

    @Test
    void observeDeploymentUsesTheSecondDeploymentAsPreviousRevision() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V2,
                List.of(Deployment.builder().status("PRIMARY").taskDefinition(TASK_DEF_V2)
                        .rolloutState("COMPLETED").desiredCount(1).runningCount(1).build(),
                    Deployment.builder().status("ACTIVE").taskDefinition(TASK_DEF_V1)
                        .rolloutState("COMPLETED").desiredCount(1).runningCount(1).build()),
                1, 1)).build());
        when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
            .thenAnswer(invocation -> {
                String arn = ((DescribeTaskDefinitionRequest) invocation.getArgument(0)).taskDefinition();
                return taskDefinition("repo/payments@" + (arn.endsWith(":1") ? "sha256:aaa" : "sha256:bbb"));
            });

        var identity = adapter.observeDeployment(context, "rollbackshield-demo/payments").orElseThrow();

        assertEquals(TASK_DEF_V2, identity.candidateRevision());
        assertEquals(TASK_DEF_V1, identity.previousRevision());
        assertEquals("sha256:bbb", identity.candidateArtifactDigest());
        assertEquals("sha256:aaa", identity.previousArtifactDigest());
        assertEquals("repo/payments", identity.artifactRepository());
        assertTrue(identity.candidateVersionLabel().endsWith("task-definition:2"));
        assertTrue(identity.previousVersionLabel().endsWith("task-definition:1"));
    }

    @Test
    void observeDeploymentFallsBackToTaskDefinitionFamilyForPreviousRevision() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service(TASK_DEF_V2, deployments(TASK_DEF_V2, "COMPLETED"), 1, 1)).build());
        ListTaskDefinitionsIterable taskDefinitions = mock(ListTaskDefinitionsIterable.class);
        when(taskDefinitions.taskDefinitionArns()).thenReturn(sdkIterable(TASK_DEF_V1, TASK_DEF_V2));
        when(ecs.listTaskDefinitionsPaginator(any(ListTaskDefinitionsRequest.class)))
            .thenReturn(taskDefinitions);
        when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
            .thenReturn(taskDefinition("repo/payments@sha256:bbb"));

        var identity = adapter.observeDeployment(context, "rollbackshield-demo/payments").orElseThrow();

        assertEquals(TASK_DEF_V1, identity.previousRevision());
    }

    @Test
    void observeDeploymentReturnsEmptyForMissingService() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service("INACTIVE", TASK_DEF_V1, deployments(TASK_DEF_V1, "COMPLETED"), 0, 0))
                .build());

        assertTrue(adapter.observeDeployment(context, "rollbackshield-demo/payments").isEmpty());
    }

    @Test
    void rollbackIsIdempotentWhenTheServiceIsAlreadyOnTheTarget() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service(TASK_DEF_V1, deployments(TASK_DEF_V1, "COMPLETED"), 1, 1)).build());

        RollbackExecutionResult result = adapter.requestRollback(context, target(TASK_DEF_V1));

        assertEquals(RollbackExecutionResult.State.COMPLETED, result.state());
        verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    void rollbackUpdatesTheServiceToTheExactPreviousTaskDefinition() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service(TASK_DEF_V2, deployments(TASK_DEF_V2, "COMPLETED"), 1, 1)).build());
        when(ecs.updateService(any(UpdateServiceRequest.class)))
            .thenReturn(software.amazon.awssdk.services.ecs.model.UpdateServiceResponse.builder().build());

        RollbackExecutionResult result = adapter.requestRollback(context, target(TASK_DEF_V1));

        assertEquals(RollbackExecutionResult.State.IN_PROGRESS, result.state());
        ArgumentCaptor<UpdateServiceRequest> request = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs).updateService(request.capture());
        assertEquals(TASK_DEF_V1, request.getValue().taskDefinition());
        assertEquals("rollbackshield-demo", request.getValue().cluster());
        assertEquals("payments", request.getValue().service());
    }

    @Test
    void monitorReportsFailureWhenEcsRolloutFailed() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V1,
                List.of(Deployment.builder().status("PRIMARY").taskDefinition(TASK_DEF_V1)
                    .rolloutState("FAILED").rolloutStateReason("task failed health checks").build()),
                1, 1)).build());

        RollbackExecutionResult result = adapter.monitorRollback(context, target(TASK_DEF_V1));

        assertEquals(RollbackExecutionResult.State.FAILED, result.state());
        assertTrue(result.detail().contains("task failed health checks"));
    }

    @Test
    void monitorReportsCompletedWhenRolloutCompleted() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V1,
                deployments(TASK_DEF_V1, "COMPLETED"), 1, 1)).build());

        assertEquals(RollbackExecutionResult.State.COMPLETED,
            adapter.monitorRollback(context, target(TASK_DEF_V1)).state());
    }

    @Test
    void healthIsDegradedWhileTasksAreStillStarting() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V2,
                List.of(Deployment.builder().status("PRIMARY").taskDefinition(TASK_DEF_V2)
                    .rolloutState("IN_PROGRESS").desiredCount(2).runningCount(1).pendingCount(1).build()),
                2, 1)).build());

        HealthObservation observation = adapter.verifyHealth(context, "rollbackshield-demo/payments");

        assertEquals(HealthObservation.HealthState.DEGRADED, observation.state());
        assertEquals("1", observation.metrics().get("runningCount"));
        assertEquals("2", observation.metrics().get("desiredCount"));
    }

    @Test
    void healthIsHealthyWhenRunningCountMatchesDesired() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder()
                .services(service(TASK_DEF_V2, deployments(TASK_DEF_V2, "COMPLETED"), 2, 2)).build());

        assertEquals(HealthObservation.HealthState.HEALTHY,
            adapter.verifyHealth(context, "rollbackshield-demo/payments").state());
    }

    @Test
    void resolvesTagOnlyImagesToImmutableDigestsViaEcr() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V2,
                List.of(Deployment.builder().status("PRIMARY").taskDefinition(TASK_DEF_V2)
                        .rolloutState("COMPLETED").desiredCount(1).runningCount(1).build(),
                    Deployment.builder().status("ACTIVE").taskDefinition(TASK_DEF_V1)
                        .rolloutState("COMPLETED").desiredCount(1).runningCount(1).build()),
                1, 1)).build());
        when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
            .thenAnswer(invocation -> {
                String arn = ((DescribeTaskDefinitionRequest) invocation.getArgument(0)).taskDefinition();
                return taskDefinition("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments:"
                    + (arn.endsWith(":1") ? "v1" : "v2"));
            });
        EcrClient ecr = mock(EcrClient.class);
        when(clients.ecr(any())).thenReturn(ecr);
        when(ecr.describeImages(any(DescribeImagesRequest.class))).thenAnswer(invocation -> {
            DescribeImagesRequest request = invocation.getArgument(0);
            String tag = request.imageIds().get(0).imageTag();
            return software.amazon.awssdk.services.ecr.model.DescribeImagesResponse.builder()
                .imageDetails(software.amazon.awssdk.services.ecr.model.ImageDetail.builder()
                    .repositoryName("payments")
                    .imageDigest("sha256:" + tag).build())
                .build();
        });

        var identity = adapter.observeDeployment(context, "rollbackshield-demo/payments").orElseThrow();

        assertEquals("sha256:v2", identity.candidateArtifactDigest());
        assertEquals("sha256:v1", identity.previousArtifactDigest());
    }

    @Test
    void leavesDigestUnknownWhenEcrCannotResolveTheTag() {
        when(ecs.describeServices(any(DescribeServicesRequest.class)))
            .thenReturn(DescribeServicesResponse.builder().services(service(TASK_DEF_V2,
                deployments(TASK_DEF_V2, "COMPLETED"), 1, 1)).build());
        when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
            .thenReturn(taskDefinition("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments:v2"));
        software.amazon.awssdk.services.ecs.paginators.ListTaskDefinitionsIterable taskDefinitions =
            mock(software.amazon.awssdk.services.ecs.paginators.ListTaskDefinitionsIterable.class);
        when(taskDefinitions.taskDefinitionArns()).thenReturn(sdkIterable());
        when(ecs.listTaskDefinitionsPaginator(any(ListTaskDefinitionsRequest.class)))
            .thenReturn(taskDefinitions);
        EcrClient ecr = mock(EcrClient.class);
        when(clients.ecr(any())).thenReturn(ecr);
        when(ecr.describeImages(any(DescribeImagesRequest.class)))
            .thenThrow(software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException.builder()
                .message("repository not found").build());

        var identity = adapter.observeDeployment(context, "rollbackshield-demo/payments").orElseThrow();

        assertEquals(null, identity.candidateArtifactDigest());
    }

    @Test
    void parsesTagsAndRepositoryNamesConservatively() {
        assertEquals("v2", EcsRuntimeAdapter.tagOf("repo/payments:v2"));
        assertEquals(null, EcsRuntimeAdapter.tagOf("repo/payments@sha256:abc"));
        assertEquals("payments", EcsRuntimeAdapter.repositoryNameOf(
            "111122223333.dkr.ecr.us-east-1.amazonaws.com/payments"));
        assertEquals("team/payments", EcsRuntimeAdapter.repositoryNameOf("team/payments"));
        assertEquals("v1", EcsRuntimeAdapter.tagOf("localhost:5000/team/payments:v1"));
    }

    private static RollbackTarget target(String revision) {
        return new RollbackTarget("rollbackshield-demo/payments", "payments", revision, "sha256:aaa", null);
    }

    private static DescribeTaskDefinitionResponse taskDefinition(String image) {
        return DescribeTaskDefinitionResponse.builder()
            .taskDefinition(TaskDefinition.builder()
                .containerDefinitions(ContainerDefinition.builder().image(image).build()).build())
            .build();
    }

    private static Service service(String taskDefinition, List<Deployment> deployments,
                                   int desired, int running) {
        return service("ACTIVE", taskDefinition, deployments, desired, running);
    }

    private static Service service(String status, String taskDefinition, List<Deployment> deployments,
                                   int desired, int running) {
        return Service.builder().serviceName("payments").serviceArn(CLUSTER + "/payments")
            .status(status).taskDefinition(taskDefinition).deployments(deployments)
            .desiredCount(desired).runningCount(running).pendingCount(Math.max(0, desired - running)).build();
    }

    private static List<Deployment> deployments(String taskDefinition, String rolloutState) {
        return List.of(Deployment.builder().status("PRIMARY").taskDefinition(taskDefinition)
            .rolloutState(rolloutState).desiredCount(1).runningCount(1).build());
    }

    private static SdkIterable<String> sdkIterable(String... values) {
        return () -> List.of(values).iterator();
    }
}
