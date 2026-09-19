package com.rollbackshield.connectors.kubernetes.adapter;

import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.shared.domain.OrganizationId;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runs the Kubernetes adapter against a real client backed by the fabric8
 * mock API server (CRUD semantics), so discovery, revision extraction and
 * health observation are exercised against a Kubernetes-shaped API rather
 * than hand-rolled stubs. Rollback execution is intentionally absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnableKubernetesMockClient(crud = true)
class KubernetesRuntimeAdapterTest {

    static KubernetesClient client;

    @Mock
    private KubernetesClients clients;

    private KubernetesRuntimeAdapter adapter;
    private ConnectorContext context;

    @BeforeEach
    void setUp() {
        adapter = new KubernetesRuntimeAdapter(clients);
        when(clients.client(any())).thenReturn(client);
        // The CRUD mock server is shared across tests; start from a clean namespace.
        client.apps().replicaSets().inNamespace("production").list().getItems()
            .forEach(rs -> client.apps().replicaSets().inNamespace("production")
                .withName(rs.getMetadata().getName()).delete());
        client.apps().deployments().inNamespace("production").list().getItems()
            .forEach(deployment -> client.apps().deployments().inNamespace("production")
                .withName(deployment.getMetadata().getName()).delete());
        Integration integration = Integration.pending(OrganizationId.newId(), "cluster",
            ConnectorType.KUBERNETES, "https://kubernetes.example",
            IntegrationCredentialReference.secret(
                IntegrationCredentialReference.CredentialKind.KUBERNETES_KUBECONFIG, "KUBECONFIG"),
            Map.of());
        context = new ConnectorContext(integration, new CredentialMaterial.Kubeconfig("apiVersion: v1"));
    }

    @Test
    void discoversDeploymentsWithRevisionAndImageMetadata() {
        client.apps().deployments().inNamespace("production").resource(deployment("payments",
            "registry.example/payments@sha256:candidate", "2", 3, 3)).create();

        List<DiscoveredResource> resources = adapter.discover(context);

        DiscoveredResource payments = resources.stream()
            .filter(resource -> resource.resourceType() == DiscoveredResourceType.KUBERNETES_DEPLOYMENT)
            .filter(resource -> resource.externalId().equals("production/payments"))
            .findFirst().orElseThrow();
        assertEquals("2", payments.metadata().get("revision"));
        assertEquals("registry.example/payments@sha256:candidate", payments.metadata().get("image"));
        assertEquals("3", payments.metadata().get("availableReplicas"));
    }

    @Test
    void observeDeploymentExtractsCandidateAndPreviousImagesFromOwnedReplicaSets() {
        Deployment deployment = client.apps().deployments().inNamespace("production")
            .resource(deployment("payments", "registry.example/payments@sha256:candidate", "3", 2, 2)).create();
        String uid = deployment.getMetadata().getUid();

        client.apps().replicaSets().inNamespace("production").resource(replicaSet(uid, "payments-old",
            "registry.example/payments@sha256:previous", "2")).create();

        var identity = adapter.observeDeployment(context, "production/payments").orElseThrow();

        assertEquals("3", identity.candidateRevision());
        assertEquals("2", identity.previousRevision());
        assertEquals("sha256:candidate", identity.candidateArtifactDigest());
        assertEquals("sha256:previous", identity.previousArtifactDigest());
        assertEquals("registry.example/payments", identity.artifactRepository());
    }

    @Test
    void observeDeploymentReturnsEmptyForMissingDeployment() {
        assertTrue(adapter.observeDeployment(context, "production/does-not-exist").isEmpty());
    }

    @Test
    void healthIsHealthyWhenAllReplicasAreAvailable() {
        client.apps().deployments().inNamespace("production")
            .resource(deployment("payments", "registry.example/payments@sha256:candidate", "2", 2, 2)).create();

        HealthObservation observation = adapter.verifyHealth(context, "production/payments");

        assertEquals(HealthObservation.HealthState.HEALTHY, observation.state());
        assertEquals("2", observation.metrics().get("availableReplicas"));
    }

    @Test
    void healthIsUnhealthyWhenNoReplicaIsAvailable() {
        client.apps().deployments().inNamespace("production")
            .resource(deployment("payments", "registry.example/payments@sha256:candidate", "2", 2, 0)).create();

        assertEquals(HealthObservation.HealthState.UNHEALTHY,
            adapter.verifyHealth(context, "production/payments").state());
    }

    private static Deployment deployment(String name, String image, String revision,
                                         int desired, int available) {
        Deployment deployment = new DeploymentBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace("production")
                .withUid("uid-" + name)
                .addToAnnotations("deployment.kubernetes.io/revision", revision)
                .addToAnnotations("kubernetes.io/change-cause", "deploy " + revision).build())
            .withNewSpec().withReplicas(desired)
                .withNewTemplate().withNewSpec()
                    .withContainers(new ContainerBuilder().withName(name).withImage(image).build())
                .endSpec().endTemplate()
            .endSpec()
            .withNewStatus().withReplicas(desired).withAvailableReplicas(available)
                .withReadyReplicas(available).endStatus()
            .build();
        return deployment;
    }

    private static io.fabric8.kubernetes.api.model.apps.ReplicaSet replicaSet(String ownerUid, String name,
                                                                              String image, String revision) {
        return new ReplicaSetBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace("production")
                .addToAnnotations("deployment.kubernetes.io/revision", revision)
                .addToOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                    .withUid(ownerUid).withKind("Deployment").withName("payments")
                    .withApiVersion("apps/v1").build())
                .build())
            .withNewSpec().withNewTemplate().withNewSpec()
                .withContainers(new ContainerBuilder().withName("payments").withImage(image).build())
            .endSpec().endTemplate().endSpec()
            .build();
    }
}
