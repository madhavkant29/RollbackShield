package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.shared.domain.IntegrationId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryDiscoveredResourceRepository implements DiscoveredResourceRepository {

    private final Map<String, DiscoveredResource> store = new ConcurrentHashMap<>();

    @Override
    public void replaceForIntegration(IntegrationId integrationId, List<DiscoveredResource> resources) {
        store.entrySet().removeIf(entry -> entry.getValue().integrationId().equals(integrationId));
        resources.forEach(resource -> store.put(resource.resourceId(), resource));
    }

    @Override
    public List<DiscoveredResource> findByIntegration(IntegrationId integrationId) {
        return store.values().stream()
            .filter(resource -> resource.integrationId().equals(integrationId))
            .collect(Collectors.toList());
    }

    @Override
    public List<DiscoveredResource> findByIntegrationAndType(IntegrationId integrationId,
                                                             DiscoveredResourceType type) {
        return store.values().stream()
            .filter(resource -> resource.integrationId().equals(integrationId)
                && resource.resourceType() == type)
            .collect(Collectors.toList());
    }

    @Override
    public java.util.Optional<DiscoveredResource> findByExternalId(IntegrationId integrationId,
                                                                   DiscoveredResourceType type,
                                                                   String externalId) {
        return store.values().stream()
            .filter(resource -> resource.integrationId().equals(integrationId)
                && resource.resourceType() == type
                && resource.externalId().equals(externalId))
            .findFirst();
    }

    @Override
    public int deleteNotIn(IntegrationId integrationId, List<String> resourceIds) {
        List<String> keep = List.copyOf(resourceIds);
        int before = store.size();
        store.entrySet().removeIf(entry -> entry.getValue().integrationId().equals(integrationId)
            && !keep.contains(entry.getValue().resourceId()));
        return before - store.size();
    }
}
