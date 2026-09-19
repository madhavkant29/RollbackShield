package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one sync run. {@code errors} is non-empty whenever the provider
 * partially failed; a sync with errors is never reported as success.
 */
public record SyncResult(
    IntegrationId integrationId,
    int discoveredCount,
    int removedCount,
    int errorCount,
    java.util.List<String> errors,
    Instant completedAt
) {

    public SyncResult {
        Objects.requireNonNull(integrationId, "integrationId");
        if (errorCount != errors.size()) {
            errorCount = errors.size();
        }
        errors = java.util.List.copyOf(errors == null ? java.util.List.of() : errors);
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public static SyncResult success(IntegrationId integrationId, int discoveredCount, int removedCount) {
        return new SyncResult(integrationId, discoveredCount, removedCount, 0, java.util.List.of(), Instant.now());
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }

    public Map<String, String> toEventPayload() {
        return Map.of(
            "discoveredCount", String.valueOf(discoveredCount),
            "removedCount", String.valueOf(removedCount),
            "errorCount", String.valueOf(errorCount));
    }
}
