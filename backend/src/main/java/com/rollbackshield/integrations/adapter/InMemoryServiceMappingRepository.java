package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryServiceMappingRepository implements ServiceMappingRepository {

    private final Map<ServiceId, ServiceMapping> store = new ConcurrentHashMap<>();

    @Override
    public ServiceMapping save(ServiceMapping mapping) {
        store.put(mapping.serviceId(), mapping);
        return mapping;
    }

    @Override
    public Optional<ServiceMapping> findByService(ServiceId serviceId) {
        return Optional.ofNullable(store.get(serviceId));
    }

    @Override
    public List<ServiceMapping> findByOrganization(OrganizationId organizationId) {
        return store.values().stream()
            .filter(mapping -> mapping.organizationId().equals(organizationId))
            .collect(Collectors.toList());
    }
}
