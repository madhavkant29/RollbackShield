package com.rollbackshield.connectors.kubernetes.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.HealthVerificationPort;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Kubernetes: deployment discovery and revision observation. Revisions come
 * from the standard {@code deployment.kubernetes.io/revision} annotation and
 * the ReplicaSets the deployment owns; nothing is inferred from image tags.
 * Rollback execution is deliberately not implemented (no ROLLBACK_EXECUTION
 * capability) -- Kubernetes rollbacks need policy decisions this product
 * should not silently make.
 */
@Component
public class KubernetesRuntimeAdapter implements CapabilityProvider, DiscoveryContributionPort,
    DeploymentObservationPort, HealthVerificationPort {

    private static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    private static final String CHANGE_CAUSE_ANNOTATION = "kubernetes.io/change-cause";
    private static final int MAX_DEPLOYMENTS = 500;

    private final KubernetesClients clients;

    public KubernetesRuntimeAdapter(KubernetesClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.KUBERNETES;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.RUNTIME_DISCOVERY, ConnectorCapability.DEPLOYMENT_STATUS,
            ConnectorCapability.HEALTH_VERIFICATION);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        KubernetesClient client = clients.client(context);
        List<Deployment> deployments = client.apps().deployments().inAnyNamespace().list().getItems();
        List<DiscoveredResource> found = new ArrayList<>();
        deployments.stream().limit(MAX_DEPLOYMENTS).forEach(deployment ->
            found.add(toResource(context, deployment)));
        return found;
    }

    @Override
    public Optional<DeploymentIdentity> observeDeployment(ConnectorContext context,
                                                          String runtimeExternalId) {
        NamespacedRef ref = NamespacedRef.parse(runtimeExternalId);
        KubernetesClient client = clients.client(context);
        Deployment deployment = client.apps().deployments()
            .inNamespace(ref.namespace()).withName(ref.name()).get();
        if (deployment == null) {
            return Optional.empty();
        }
        String candidateRevision = annotation(deployment, REVISION_ANNOTATION);
        String candidateImage = firstImage(deployment);
        List<ReplicaSet> replicaSets = ownedReplicaSets(client, deployment);
        ReplicaSet previous = previousReplicaSet(replicaSets, candidateRevision);
        String previousRevision = previous == null ? null : annotation(previous, REVISION_ANNOTATION);
        String previousImage = previous == null ? null : firstImage(previous);
        String rolloutState = deployment.getStatus() == null ? "UNKNOWN" : deploymentState(deployment);

        return Optional.of(new DeploymentIdentity(
            deployment.getMetadata().getName(),
            runtimeExternalId,
            candidateRevision,
            previousRevision,
            DeploymentIdentity.artifactDigest(candidateImage),
            DeploymentIdentity.artifactDigest(previousImage),
            null,
            null,
            DeploymentIdentity.artifactRepository(candidateImage),
            rolloutState));
    }

    @Override
    public HealthObservation verifyHealth(ConnectorContext context, String runtimeExternalId) {
        NamespacedRef ref = NamespacedRef.parse(runtimeExternalId);
        KubernetesClient client = clients.client(context);
        Deployment deployment = client.apps().deployments()
            .inNamespace(ref.namespace()).withName(ref.name()).get();
        if (deployment == null) {
            return new HealthObservation(HealthObservation.HealthState.UNHEALTHY,
                "deployment not found", Instant.now(), Map.of());
        }
        int desired = deployment.getSpec().getReplicas() == null ? 0 : deployment.getSpec().getReplicas();
        int available = deployment.getStatus() == null
            || deployment.getStatus().getAvailableReplicas() == null ? 0
            : deployment.getStatus().getAvailableReplicas();
        int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null ? 0
            : deployment.getStatus().getReadyReplicas();
        boolean progressing = deployment.getStatus() != null
            && deployment.getStatus().getConditions() != null
            && deployment.getStatus().getConditions().stream().anyMatch(condition ->
                "Progressing".equals(condition.getType())
                    && "False".equals(condition.getStatus()));

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("desiredReplicas", String.valueOf(desired));
        metrics.put("availableReplicas", String.valueOf(available));
        metrics.put("readyReplicas", String.valueOf(ready));

        if (desired == 0) {
            return new HealthObservation(HealthObservation.HealthState.UNKNOWN,
                "deployment scaled to zero; health cannot be verified", Instant.now(), metrics);
        }
        if (available == 0) {
            return new HealthObservation(HealthObservation.HealthState.UNHEALTHY,
                "no available replicas", Instant.now(), metrics);
        }
        if (available < desired || progressing) {
            return new HealthObservation(HealthObservation.HealthState.DEGRADED,
                "available " + available + "/" + desired + " replicas",
                Instant.now(), metrics);
        }
        return new HealthObservation(HealthObservation.HealthState.HEALTHY,
            "available " + available + "/" + desired + " replicas", Instant.now(), metrics);
    }

    private DiscoveredResource toResource(ConnectorContext context, Deployment deployment) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("namespace", deployment.getMetadata().getNamespace());
        metadata.put("revision", annotation(deployment, REVISION_ANNOTATION));
        metadata.put("changeCause", annotation(deployment, CHANGE_CAUSE_ANNOTATION));
        metadata.put("image", String.valueOf(firstImage(deployment)));
        metadata.put("desiredReplicas", String.valueOf(deployment.getSpec().getReplicas()));
        metadata.put("availableReplicas", deployment.getStatus() == null
            ? "0" : String.valueOf(deployment.getStatus().getAvailableReplicas()));
        metadata.put("deploymentState", deploymentState(deployment));
        String externalId = deployment.getMetadata().getNamespace() + "/" + deployment.getMetadata().getName();
        return DiscoveredResource.of(context.integration().id(), ConnectorType.KUBERNETES,
            DiscoveredResourceType.KUBERNETES_DEPLOYMENT, externalId,
            deployment.getMetadata().getName(), context.config("region", null), metadata);
    }

    private static List<ReplicaSet> ownedReplicaSets(KubernetesClient client, Deployment deployment) {
        String uid = deployment.getMetadata().getUid();
        List<ReplicaSet> replicaSets = client.apps().replicaSets()
            .inNamespace(deployment.getMetadata().getNamespace()).list().getItems();
        List<ReplicaSet> owned = new ArrayList<>();
        for (ReplicaSet replicaSet : replicaSets) {
            List<OwnerReference> owners = replicaSet.getMetadata().getOwnerReferences();
            if (owners == null) {
                continue;
            }
            boolean isOwned = owners.stream().anyMatch(owner -> owner.getUid() != null
                && owner.getUid().equals(uid));
            if (isOwned) {
                owned.add(replicaSet);
            }
        }
        return owned;
    }

    /**
     * The previous ReplicaSet is the owned ReplicaSet with the highest
     * revision strictly below the candidate's. Falls back to the
     * second-newest owned ReplicaSet when revision annotations are absent.
     */
    private static ReplicaSet previousReplicaSet(List<ReplicaSet> replicaSets, String candidateRevision) {
        if (replicaSets.isEmpty()) {
            return null;
        }
        int candidateNumber = revisionNumber(candidateRevision);
        if (candidateNumber > 0) {
            return replicaSets.stream()
                .filter(replicaSet -> revisionNumber(annotation(replicaSet, REVISION_ANNOTATION)) > 0)
                .filter(replicaSet -> revisionNumber(annotation(replicaSet, REVISION_ANNOTATION))
                    < candidateNumber)
                .max(Comparator.comparingInt(replicaSet ->
                    revisionNumber(annotation(replicaSet, REVISION_ANNOTATION))))
                .orElse(null);
        }
        List<ReplicaSet> sorted = replicaSets.stream()
            .sorted(Comparator.comparing((ReplicaSet replicaSet) ->
                replicaSet.getMetadata().getCreationTimestamp() == null
                    ? "" : replicaSet.getMetadata().getCreationTimestamp()).reversed())
            .toList();
        return sorted.size() > 1 ? sorted.get(1) : null;
    }

    private static int revisionNumber(String revision) {
        if (revision == null || revision.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(revision.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String firstImage(Deployment deployment) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().stream()
            .map(Container::getImage).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String firstImage(ReplicaSet replicaSet) {
        return replicaSet.getSpec().getTemplate().getSpec().getContainers().stream()
            .map(Container::getImage).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static String annotation(Deployment deployment, String key) {
        Map<String, String> annotations = deployment.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(key);
    }

    private static String annotation(ReplicaSet replicaSet, String key) {
        Map<String, String> annotations = replicaSet.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(key);
    }

    private static String deploymentState(Deployment deployment) {
        if (deployment.getStatus() == null || deployment.getStatus().getConditions() == null) {
            return "UNKNOWN";
        }
        return deployment.getStatus().getConditions().stream()
            .map(condition -> condition.getType() + "=" + condition.getStatus())
            .reduce((left, right) -> left + "," + right).orElse("UNKNOWN");
    }

    /** "namespace/name" external id. */
    record NamespacedRef(String namespace, String name) {

        static NamespacedRef parse(String externalId) {
            int slash = externalId.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException(
                    "Kubernetes runtime external id must be '<namespace>/<name>': " + externalId);
            }
            return new NamespacedRef(externalId.substring(0, slash), externalId.substring(slash + 1));
        }
    }
}

