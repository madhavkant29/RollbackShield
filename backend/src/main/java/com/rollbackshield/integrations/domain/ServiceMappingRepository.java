package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;

import java.util.List;
import java.util.Optional;

public interface ServiceMappingRepository {

    ServiceMapping save(ServiceMapping mapping);

    Optional<ServiceMapping> findByService(ServiceId serviceId);

    List<ServiceMapping> findByOrganization(OrganizationId organizationId);
}
