package com.rollbackshield.integrations.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class IntegrationDtos {

    private IntegrationDtos() {
    }

    public record CredentialReferenceDto(
        @NotBlank String kind,
        String secretReference,
        String roleArn,
        String externalId
    ) {
    }

    public record CreateIntegrationRequest(
        @NotBlank String name,
        @NotBlank String type,
        String endpoint,
        @NotNull @Valid CredentialReferenceDto credential,
        Map<String, String> configuration
    ) {
    }

    public record IntegrationResponse(
        String integrationId,
        String organizationId,
        String name,
        String connectorType,
        String category,
        String endpoint,
        String connectionState,
        String healthState,
        String healthDetail,
        String lastAttemptedSyncAt,
        String lastSuccessfulSyncAt,
        String lastError,
        List<String> capabilities,
        Map<String, String> configuration,
        int discoveredResourceCount,
        String createdAt
    ) {
    }

    public record ConnectionTestResponse(
        boolean success,
        String message,
        String checkedAt,
        Map<String, String> details
    ) {
    }

    public record DiscoveredResourceResponse(
        String resourceId,
        String resourceType,
        String externalId,
        String displayName,
        String region,
        Map<String, String> metadata,
        String discoveredAt
    ) {
    }

    public record SyncResultResponse(
        String integrationId,
        int discoveredCount,
        int removedCount,
        int errorCount,
        List<String> errors,
        String completedAt
    ) {
    }
}
