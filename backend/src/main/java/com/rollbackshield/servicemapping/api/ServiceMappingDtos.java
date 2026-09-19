package com.rollbackshield.servicemapping.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class ServiceMappingDtos {

    private ServiceMappingDtos() {
    }

    public record ImportServiceRequest(
        @NotBlank String integrationId,
        @NotBlank String resourceExternalId,
        String name
    ) {
    }

    public record BindingResponse(
        String role,
        String integrationId,
        String resourceType,
        String externalId,
        String confidence,
        String evidence,
        String boundAt
    ) {
    }

    public record MappingResponse(
        String serviceId,
        String mappingId,
        List<BindingResponse> bindings,
        String updatedAt
    ) {
    }

    public record ImportedServiceResponse(
        String serviceId,
        String organizationId,
        String name,
        MappingResponse mapping
    ) {
    }

    public record AddBindingRequest(
        @NotBlank String role,
        @NotBlank String integrationId,
        @NotBlank String externalId,
        String evidence
    ) {
    }
}
