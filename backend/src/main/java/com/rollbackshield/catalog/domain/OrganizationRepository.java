package com.rollbackshield.catalog.domain;

import com.rollbackshield.shared.domain.OrganizationId;

import java.util.Optional;

public interface OrganizationRepository {
    Organization save(Organization organization);
    Optional<Organization> findById(OrganizationId id);
}
