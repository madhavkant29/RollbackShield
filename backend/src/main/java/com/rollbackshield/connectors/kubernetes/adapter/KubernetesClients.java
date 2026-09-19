package com.rollbackshield.connectors.kubernetes.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches Kubernetes clients per integration so discovery reuses
 * connection pools. Clients are closed when the application shuts down;
 * cache entries are keyed by integration id and master URL, so changing
 * either yields a fresh client rather than a stale connection.
 */
@Component
public class KubernetesClients {

    private final Map<String, KubernetesClient> cache = new ConcurrentHashMap<>();

    public KubernetesClient client(ConnectorContext context) {
        return cache.computeIfAbsent(cacheKey(context), key -> build(context));
    }

    private KubernetesClient build(ConnectorContext context) {
        CredentialMaterial material = context.credentials();
        if (!(material instanceof CredentialMaterial.Kubeconfig kubeconfig)) {
            throw new IllegalArgumentException("Kubernetes connector requires a kubeconfig credential; got "
                + material.getClass().getSimpleName());
        }
        Config config = Config.fromKubeconfig(kubeconfig.yaml());
        String masterUrl = context.config("masterUrl", null);
        if (masterUrl != null && !masterUrl.isBlank()) {
            config = new ConfigBuilder(config).withMasterUrl(masterUrl).build();
        }
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private static String cacheKey(ConnectorContext context) {
        return context.integration().id() + "|" + context.config("masterUrl", context.endpoint());
    }

    @PreDestroy
    void closeAll() {
        cache.values().forEach(KubernetesClient::close);
        cache.clear();
    }
}
