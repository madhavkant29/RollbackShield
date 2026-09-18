package com.rollbackshield.catalog.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;

import java.util.List;
import java.util.Optional;

public interface AppServiceRepository {
    AppService save(AppService service);
    Optional<AppService> findById(ServiceId id);
    List<AppService> findByOrganization(OrganizationId organizationId);
}
