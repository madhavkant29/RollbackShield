package com.rollbackshield.catalog.domain;

import com.rollbackshield.shared.domain.OrganizationId;

import java.time.Instant;
import java.util.Objects;

public record Organization(OrganizationId id, String name, Instant createdAt) {
    public Organization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
