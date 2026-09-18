package com.rollbackshield.catalog.adapter;

import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.catalog.domain.OrganizationRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryOrganizationRepository implements OrganizationRepository {

    private final Map<OrganizationId, Organization> store = new ConcurrentHashMap<>();

    @Override
    public Organization save(Organization organization) {
        store.put(organization.id(), organization);
        return organization;
    }

    @Override
    public Optional<Organization> findById(OrganizationId id) {
        return Optional.ofNullable(store.get(id));
    }
}
