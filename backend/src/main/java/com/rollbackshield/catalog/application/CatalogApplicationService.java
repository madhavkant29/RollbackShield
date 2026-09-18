package com.rollbackshield.catalog.application;

import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.AppServiceRepository;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.catalog.domain.OrganizationRepository;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CatalogApplicationService {

    private final OrganizationRepository organizations;
    private final AppServiceRepository services;

    public CatalogApplicationService(OrganizationRepository organizations, AppServiceRepository services) {
        this.organizations = organizations;
        this.services = services;
    }

    public Organization createOrganization(String name) {
        Organization organization = new Organization(OrganizationId.newId(), name, Instant.now());
        return organizations.save(organization);
    }

    /** serviceOrganizationId must equal the caller's authenticated organizationId (§30). */
    public AppService createService(OrganizationId serviceOrganizationId, String name) {
        organizations.findById(serviceOrganizationId)
            .orElseThrow(() -> new NotFoundException("ORGANIZATION_NOT_FOUND",
                "No organization " + serviceOrganizationId));
        AppService service = new AppService(ServiceId.newId(), serviceOrganizationId, name, Instant.now());
        return services.save(service);
    }

    public List<AppService> listServices(OrganizationId organizationId) {
        return services.findByOrganization(organizationId);
    }

    public AppService getService(ServiceId serviceId, OrganizationId callerOrganizationId) {
        AppService service = services.findById(serviceId)
            .orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "No service " + serviceId));
        if (!service.organizationId().equals(callerOrganizationId)) {
            // Tenant boundary: never leak existence of another org's service (§30, §37 IDOR).
            throw new NotFoundException("SERVICE_NOT_FOUND", "No service " + serviceId);
        }
        return service;
    }
}
