package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.RollbackTarget;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.HealthVerificationPort;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionPort;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ecr.model.ImageDetail;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ECS: the runtime connector. Everything here is read from the ECS API --
 * current task definition, previous task definition, container images,
 * deployment rollout state -- and rollback is a real UpdateService back to a
 * concrete task-definition revision, followed by monitoring until ECS
 * reports the new deployment COMPLETED.
 */
@Component
public class EcsRuntimeAdapter implements CapabilityProvider, DiscoveryContributionPort,
    DeploymentObservationPort, RollbackExecutionPort, HealthVerificationPort {

    private static final int DESCRIBE_BATCH = 10;

    private final AwsClients clients;

    public EcsRuntimeAdapter(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.RUNTIME_DISCOVERY, ConnectorCapability.DEPLOYMENT_STATUS,
            ConnectorCapability.ROLLBACK_EXECUTION, ConnectorCapability.HEALTH_VERIFICATION);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        EcsClient ecs = clients.ecs(context);
        List<DiscoveredResource> found = new ArrayList<>();
        for (String clusterArn : ecs.listClustersPaginator().clusterArns()) {
            Cluster cluster = describeCluster(ecs, clusterArn);
            String clusterName = cluster != null ? cluster.clusterName() : clusterArn;
            Map<String, String> clusterMetadata = new LinkedHashMap<>();
            clusterMetadata.put("clusterArn", clusterArn);
            if (cluster != null) {
                clusterMetadata.put("status", String.valueOf(cluster.status()));
                clusterMetadata.put("activeServicesCount", String.valueOf(cluster.activeServicesCount()));
                clusterMetadata.put("runningTasksCount", String.valueOf(cluster.runningTasksCount()));
            }
            found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
                DiscoveredResourceType.RUNTIME_CLUSTER, clusterArn, clusterName, region(context), clusterMetadata));

            List<String> serviceArns = ecs.listServicesPaginator(ListServicesRequest.builder().cluster(clusterArn).build())
                .serviceArns().stream().toList();
            for (int offset = 0; offset < serviceArns.size(); offset += DESCRIBE_BATCH) {
                List<String> batch = serviceArns.subList(offset,
                    Math.min(offset + DESCRIBE_BATCH, serviceArns.size()));
                for (Service service : describeServices(ecs, clusterArn, batch)) {
                    found.add(serviceResource(ecs, context, clusterName, service));
                }
            }
        }
        return found;
    }

    @Override
    public Optional<DeploymentIdentity> observeDeployment(ConnectorContext context,
                                                          String runtimeExternalId) {
        EcsClient ecs = clients.ecs(context);
        Service service = describeSingleService(ecs, runtimeExternalId);
        if (service == null) {
            return Optional.empty();
        }
        String candidateRevision = service.taskDefinition();
        String previousRevision = previousTaskDefinition(ecs, service, candidateRevision);
        String candidateImage = imageOfTaskDefinition(ecs, candidateRevision);
        String previousImage = previousRevision == null ? null
            : imageOfTaskDefinition(ecs, previousRevision);

        return Optional.of(new DeploymentIdentity(
            service.serviceName(),
            runtimeExternalId,
            candidateRevision,
            previousRevision,
            resolveDigest(context, candidateImage),
            resolveDigest(context, previousImage),
            null,
            null,
            DeploymentIdentity.artifactRepository(candidateImage),
            String.valueOf(service.status())));
    }

    @Override
    public RollbackExecutionResult requestRollback(ConnectorContext context, RollbackTarget target) {
        EcsClient ecs = clients.ecs(context);
        RuntimeRef ref = RuntimeRef.parse(target.runtimeExternalId());
        Service service = describeSingleService(ecs, target.runtimeExternalId());
        if (service == null) {
            return RollbackExecutionResult.failed("ecs:DescribeServices",
                "Service " + target.runtimeExternalId() + " not found in " + region(context), Map.of());
        }
        if (target.targetRevision().equals(service.taskDefinition())) {
            return RollbackExecutionResult.completed("ecs:UpdateService",
                "service is already on " + DeploymentIdentity.shortRevision(target.targetRevision()), Map.of());
        }
        try {
            ecs.updateService(UpdateServiceRequest.builder().cluster(ref.cluster()).service(ref.service()).taskDefinition(target.targetRevision()).build());
            return RollbackExecutionResult.inProgress("ecs:UpdateService",
                "requested rollback to " + DeploymentIdentity.shortRevision(target.targetRevision()),
                Map.of("targetRevision", target.targetRevision()));
        } catch (RuntimeException e) {
            return RollbackExecutionResult.failed("ecs:UpdateService",
                "UpdateService failed: " + safeMessage(e), Map.of());
        }
    }

    @Override
    public RollbackExecutionResult monitorRollback(ConnectorContext context, RollbackTarget target) {
        EcsClient ecs = clients.ecs(context);
        Service service = describeSingleService(ecs, target.runtimeExternalId());
        if (service == null) {
            return RollbackExecutionResult.failed("ecs:DescribeServices",
                "Service " + target.runtimeExternalId() + " disappeared during rollback", Map.of());
        }
        if (!target.targetRevision().equals(service.taskDefinition())) {
            return RollbackExecutionResult.inProgress("ecs:DescribeServices",
                "waiting for task definition to converge on "
                    + DeploymentIdentity.shortRevision(target.targetRevision()), Map.of());
        }
        Deployment primary = primaryDeployment(service);
        if (primary != null && "FAILED".equals(String.valueOf(primary.rolloutState()))) {
            return RollbackExecutionResult.failed("ecs:DescribeServices",
                "ECS rollout FAILED: " + primary.rolloutStateReason(), Map.of());
        }
        boolean completed = (primary != null && "COMPLETED".equals(String.valueOf(primary.rolloutState())))
            || (service.deployments().isEmpty() && service.runningCount() == service.desiredCount());
        if (completed) {
            return RollbackExecutionResult.completed("ecs:DescribeServices",
                "service is running " + DeploymentIdentity.shortRevision(service.taskDefinition()),
                Map.of("runningCount", String.valueOf(service.runningCount()),
                    "desiredCount", String.valueOf(service.desiredCount())));
        }
        return RollbackExecutionResult.inProgress("ecs:DescribeServices", "rollout still progressing",
            Map.of("rolloutState", primary == null ? "UNKNOWN" : String.valueOf(primary.rolloutState())));
    }

    @Override
    public HealthObservation verifyHealth(ConnectorContext context, String runtimeExternalId) {
        EcsClient ecs = clients.ecs(context);
        Service service = describeSingleService(ecs, runtimeExternalId);
        if (service == null) {
            return new HealthObservation(HealthObservation.HealthState.UNHEALTHY,
                "service not found", java.time.Instant.now(), Map.of());
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("desiredCount", String.valueOf(service.desiredCount()));
        metrics.put("runningCount", String.valueOf(service.runningCount()));
        metrics.put("pendingCount", String.valueOf(service.pendingCount()));
        Deployment primary = primaryDeployment(service);
        String rolloutState = primary == null ? "UNKNOWN" : String.valueOf(primary.rolloutState());
        metrics.put("rolloutState", rolloutState);

        if ("FAILED".equals(rolloutState)) {
            return new HealthObservation(HealthObservation.HealthState.UNHEALTHY,
                "deployment rollout FAILED: "
                    + (primary == null ? "" : String.valueOf(primary.rolloutStateReason())),
                java.time.Instant.now(), metrics);
        }
        if (service.desiredCount() == 0 && service.runningCount() == 0) {
            return new HealthObservation(HealthObservation.HealthState.UNKNOWN,
                "service is scaled to zero; health cannot be verified", java.time.Instant.now(), metrics);
        }
        if (service.runningCount() == 0 && service.desiredCount() > 0) {
            return new HealthObservation(HealthObservation.HealthState.UNHEALTHY,
                "no running tasks", java.time.Instant.now(), metrics);
        }
        if (service.runningCount() < service.desiredCount() || "IN_PROGRESS".equals(rolloutState)) {
            return new HealthObservation(HealthObservation.HealthState.DEGRADED,
                "running " + service.runningCount() + "/" + service.desiredCount()
                    + " tasks, rollout " + rolloutState, java.time.Instant.now(), metrics);
        }
        return new HealthObservation(HealthObservation.HealthState.HEALTHY,
            "running " + service.runningCount() + "/" + service.desiredCount() + " tasks", java.time.Instant.now(),
            metrics);
    }

    private DiscoveredResource serviceResource(EcsClient ecs, ConnectorContext context, String clusterName,
                                               Service service) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("cluster", clusterName);
        metadata.put("serviceArn", service.serviceArn());
        metadata.put("taskDefinition", String.valueOf(service.taskDefinition()));
        metadata.put("image", String.valueOf(imageOfTaskDefinition(ecs, service.taskDefinition())));
        metadata.put("desiredCount", String.valueOf(service.desiredCount()));
        metadata.put("runningCount", String.valueOf(service.runningCount()));
        metadata.put("status", String.valueOf(service.status()));
        String externalId = clusterName + "/" + service.serviceName();
        return DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
            DiscoveredResourceType.RUNTIME_SERVICE, externalId, service.serviceName(),
            region(context), metadata);
    }

    private String region(ConnectorContext context) {
        return clients.region(context).id();
    }

    private static Cluster describeCluster(EcsClient ecs, String clusterArn) {
        var response = ecs.describeClusters(DescribeClustersRequest.builder().clusters(clusterArn).build());
        return response.clusters().isEmpty() ? null : response.clusters().get(0);
    }

    private static List<Service> describeServices(EcsClient ecs, String clusterArn, List<String> serviceArns) {
        var response = ecs.describeServices(DescribeServicesRequest.builder()
            .cluster(clusterArn).services(serviceArns).build());
        return response.services();
    }

    private static Service describeSingleService(EcsClient ecs, String runtimeExternalId) {
        RuntimeRef ref = RuntimeRef.parse(runtimeExternalId);
        var response = ecs.describeServices(DescribeServicesRequest.builder()
            .cluster(ref.cluster()).services(ref.service()).build());
        if (response.services().isEmpty()) {
            return null;
        }
        Service service = response.services().get(0);
        // ECS returns a placeholder with status INACTIVE for missing services.
        return "INACTIVE".equals(String.valueOf(service.status())) ? null : service;
    }

    private static String previousTaskDefinition(EcsClient ecs, Service service, String candidateRevision) {
        int candidateNumber = revisionNumber(candidateRevision);
        return service.deployments().stream()
            .map(Deployment::taskDefinition)
            .filter(taskDefinition -> taskDefinition != null && !taskDefinition.equals(candidateRevision))
            .filter(taskDefinition -> revisionNumber(taskDefinition) < candidateNumber)
            .max(Comparator.comparingInt(EcsRuntimeAdapter::revisionNumber))
            .orElseGet(() -> previousTaskDefinitionFromFamily(ecs, candidateRevision));
    }

    private static String previousTaskDefinitionFromFamily(EcsClient ecs, String candidateRevision) {
        int candidateNumber = revisionNumber(candidateRevision);
        if (candidateNumber <= 0) {
            return null;
        }
        String family = familyOf(candidateRevision);
        return ecs.listTaskDefinitionsPaginator(ListTaskDefinitionsRequest.builder().familyPrefix(family).build())
            .taskDefinitionArns().stream()
            .filter(arn -> revisionNumber(arn) < candidateNumber)
            .max(Comparator.comparingInt(EcsRuntimeAdapter::revisionNumber))
            .orElse(null);
    }

    private static String imageOfTaskDefinition(EcsClient ecs, String taskDefinitionArn) {
        try {
            TaskDefinition taskDefinition = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefinitionArn).build())
                .taskDefinition();
            return firstImage(taskDefinition.containerDefinitions());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The immutable digest for an image reference. A pinned
     * {@code image@sha256:...} is used directly; a tag-only reference is
     * resolved through ECR's DescribeImages, because a mutable tag alone is
     * not artifact identity. If the repository or tag is not visible to this
     * role, the digest stays null and preflight reports it as unknown rather
     * than inventing one.
     */
    private String resolveDigest(ConnectorContext context, String image) {
        if (image == null) {
            return null;
        }
        String pinned = DeploymentIdentity.artifactDigest(image);
        if (pinned != null) {
            return pinned;
        }
        String repository = DeploymentIdentity.artifactRepository(image);
        String tag = tagOf(image);
        if (repository == null || tag == null) {
            return null;
        }
        try {
            var response = clients.ecr(context).describeImages(DescribeImagesRequest.builder()
                .repositoryName(repositoryNameOf(repository))
                .imageIds(ImageIdentifier.builder().imageTag(tag).build())
                .build());
            return response.imageDetails().stream()
                .map(ImageDetail::imageDigest)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Mutable tag from an image reference, or null for a digest-pinned image. */
    static String tagOf(String image) {
        int at = image.indexOf('@');
        if (at >= 0) {
            return null;
        }
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon > lastSlash ? image.substring(lastColon + 1) : null;
    }

    /** ECR repository name from a registry-qualified repository reference. */
    static String repositoryNameOf(String repository) {
        int firstSlash = repository.indexOf('/');
        if (firstSlash > 0 && repository.substring(0, firstSlash).contains(".")) {
            return repository.substring(firstSlash + 1);
        }
        return repository;
    }

    private static String firstImage(List<ContainerDefinition> definitions) {
        return definitions.stream().map(ContainerDefinition::image).filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
    }

    private static Deployment primaryDeployment(Service service) {
        return service.deployments().stream()
            .filter(deployment -> "PRIMARY".equals(String.valueOf(deployment.status())))
            .findFirst()
            .orElseGet(() -> service.deployments().stream().findFirst().orElse(null));
    }


    private static String familyOf(String taskDefinitionArn) {
        int slash = taskDefinitionArn.lastIndexOf('/');
        int colon = taskDefinitionArn.lastIndexOf(':');
        if (slash < 0 || colon <= slash) {
            return taskDefinitionArn;
        }
        return taskDefinitionArn.substring(slash + 1, colon);
    }

    private static int revisionNumber(String taskDefinitionArn) {
        if (taskDefinitionArn == null) {
            return -1;
        }
        int colon = taskDefinitionArn.lastIndexOf(':');
        if (colon < 0 || colon == taskDefinitionArn.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(taskDefinitionArn.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** "cluster/service" external id, tolerant of service names containing '/'. */
    record RuntimeRef(String cluster, String service) {

        static RuntimeRef parse(String externalId) {
            int slash = externalId.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException(
                    "ECS runtime external id must be '<cluster>/<service>': " + externalId);
            }
            return new RuntimeRef(externalId.substring(0, slash), externalId.substring(slash + 1));
        }
    }
}



