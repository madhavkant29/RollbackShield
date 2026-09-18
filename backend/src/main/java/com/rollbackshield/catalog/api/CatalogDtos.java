package com.rollbackshield.catalog.api;

import jakarta.validation.constraints.NotBlank;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreateOrganizationRequest(@NotBlank String name) {
    }

    public record OrganizationResponse(String organizationId, String name) {
    }

    public record CreateServiceRequest(@NotBlank String name) {
    }

    public record ServiceResponse(String serviceId, String organizationId, String name) {
    }
}
