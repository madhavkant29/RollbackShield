package com.rollbackshield.catalog.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;

import java.time.Instant;
import java.util.Objects;

/**
 * Named AppService (not "Service") to avoid colliding with Spring's
 * @Service stereotype in files that import both.
 */
public record AppService(ServiceId id, OrganizationId organizationId, String name, Instant createdAt) {
    public AppService {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
