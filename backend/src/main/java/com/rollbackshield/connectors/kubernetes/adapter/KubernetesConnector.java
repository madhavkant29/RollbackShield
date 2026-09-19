package com.rollbackshield.connectors.kubernetes.adapter;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Kubernetes family connector: owns the real connection test (API server
 * reachability through the kubeconfig) so the integration can be created,
 * tested and synced through the same architecture as every other provider.
 * Capabilities come from the runtime adapter; this class contributes only
 * the connection test.
 */
@Component
public class KubernetesConnector implements Connector {

    private final KubernetesClients clients;

    public KubernetesConnector(KubernetesClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.KUBERNETES;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
        supportedCredentialKinds() {
        return Set.of(com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
            .KUBERNETES_KUBECONFIG);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorContext context) {
        try (KubernetesClient client = clients.client(context)) {
            var version = client.getKubernetesVersion();
            Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("gitVersion", version.getGitVersion());
            details.put("platform", version.getPlatform());
            details.put("namespaces", String.valueOf(
                client.namespaces().list().getItems().size()));
            return ConnectionTestResult.ok("Kubernetes API reachable; server "
                + version.getGitVersion(), details);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Kubernetes API call failed: " + safeMessage(e),
                Map.of("masterUrl", String.valueOf(context.config("masterUrl", context.endpoint()))));
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
