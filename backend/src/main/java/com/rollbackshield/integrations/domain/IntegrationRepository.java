package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;

import java.util.List;
import java.util.Optional;

public interface IntegrationRepository {

    Integration save(Integration integration);

    Optional<Integration> findById(IntegrationId id);

    List<Integration> findByOrganization(OrganizationId organizationId);

    void delete(IntegrationId id);
}
