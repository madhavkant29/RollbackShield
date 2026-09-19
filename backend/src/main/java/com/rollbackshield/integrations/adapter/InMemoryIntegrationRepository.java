package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryIntegrationRepository implements IntegrationRepository {

    private final Map<IntegrationId, Integration> store = new ConcurrentHashMap<>();

    @Override
    public Integration save(Integration integration) {
        store.put(integration.id(), integration);
        return integration;
    }

    @Override
    public Optional<Integration> findById(IntegrationId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Integration> findByOrganization(OrganizationId organizationId) {
        return store.values().stream()
            .filter(integration -> integration.organizationId().equals(organizationId))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(IntegrationId id) {
        store.remove(id);
    }
}
