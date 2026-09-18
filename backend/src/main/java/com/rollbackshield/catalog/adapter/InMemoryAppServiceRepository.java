package com.rollbackshield.catalog.adapter;

import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.AppServiceRepository;
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
public class InMemoryAppServiceRepository implements AppServiceRepository {

    private final Map<ServiceId, AppService> store = new ConcurrentHashMap<>();

    @Override
    public AppService save(AppService service) {
        store.put(service.id(), service);
        return service;
    }

    @Override
    public Optional<AppService> findById(ServiceId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AppService> findByOrganization(OrganizationId organizationId) {
        return store.values().stream()
            .filter(s -> s.organizationId().equals(organizationId))
            .collect(Collectors.toList());
    }
}
