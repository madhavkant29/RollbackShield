package com.rollbackshield.integrations.domain;

import java.time.Instant;

/**
 * The synchronization aspect of an integration. {@code lastSuccessfulAt} and
 * {@code lastDiscoveredCount} are only ever set by a sync that actually
 * completed; a failed attempt preserves the last real success so an outage
 * never erases observed state.
 */
public record SyncState(
    SyncStatus status,
    Instant lastAttemptedAt,
    Instant lastSuccessfulAt,
    int lastDiscoveredCount,
    String lastError
) {

    public SyncState {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (lastDiscoveredCount < 0) {
            throw new IllegalArgumentException("lastDiscoveredCount must not be negative");
        }
    }

    public enum SyncStatus {
        NEVER_SYNCED,
        SUCCEEDED,
        FAILED
    }

    public static SyncState never() {
        return new SyncState(SyncStatus.NEVER_SYNCED, null, null, 0, null);
    }

    /** Records the attempt without changing the outcome of the previous one. */
    public SyncState withAttempt(Instant now) {
        return new SyncState(status, now, lastSuccessfulAt, lastDiscoveredCount, lastError);
    }

    public SyncState succeeded(Instant now, int discoveredCount) {
        return new SyncState(SyncStatus.SUCCEEDED, now, now, discoveredCount, null);
    }

    public SyncState failed(Instant now, String error) {
        return new SyncState(SyncStatus.FAILED, now, lastSuccessfulAt, lastDiscoveredCount, error);
    }

    public boolean hasEverSucceeded() {
        return lastSuccessfulAt != null;
    }
}
