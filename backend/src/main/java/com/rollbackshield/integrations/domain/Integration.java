package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A connection to an external system. Credentials are referenced, not
 * embedded. The connection and synchronization aspects are explicit value
 * objects: {@link IntegrationConnection#state()} only changes from a real
 * provider connection test, and {@link SyncState#lastSuccessfulAt()} only
 * from a sync that actually completed, so the UI cannot claim CONNECTED or
 * "synced" without evidence.
 */
public record Integration(
    IntegrationId id,
    OrganizationId organizationId,
    String name,
    ConnectorType type,
    String endpoint,
    IntegrationCredentialReference credential,
    Map<String, String> configuration,
    IntegrationConnection connection,
    SyncState syncState,
    Instant createdAt,
    Instant updatedAt
) {

    public Integration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(syncState, "syncState");
        configuration = Map.copyOf(configuration == null ? Map.of() : configuration);
    }

    public static Integration pending(OrganizationId organizationId, String name, ConnectorType type,
                                      String endpoint, IntegrationCredentialReference credential,
                                      Map<String, String> configuration) {
        Instant now = Instant.now();
        return new Integration(IntegrationId.newId(), organizationId, name, type, endpoint,
            credential, configuration, IntegrationConnection.pending(now), SyncState.never(), now, now);
    }

    public Integration withConnectionResult(ConnectionTestResult result) {
        return new Integration(id, organizationId, name, type, endpoint, credential, configuration,
            connection.withTestResult(result), syncState, createdAt, Instant.now());
    }

    public Integration recordSyncAttempt() {
        Instant now = Instant.now();
        return new Integration(id, organizationId, name, type, endpoint, credential, configuration,
            connection, syncState.withAttempt(now), createdAt, now);
    }

    public Integration recordSyncSuccess(ConnectorHealth syncHealth, int discoveredCount) {
        Instant now = Instant.now();
        return new Integration(id, organizationId, name, type, endpoint, credential, configuration,
            connection.asConnected(syncHealth, now), syncState.succeeded(now, discoveredCount),
            createdAt, now);
    }

    public Integration recordSyncFailure(String error) {
        Instant now = Instant.now();
        ConnectorHealth failureHealth = new ConnectorHealth(
            ConnectorHealth.HealthState.UNHEALTHY, error, now);
        return new Integration(id, organizationId, name, type, endpoint, credential, configuration,
            connection.withHealth(failureHealth, now), syncState.failed(now, error), createdAt, now);
    }

    public Integration disconnect() {
        return new Integration(id, organizationId, name, type, endpoint, credential, configuration,
            connection.disconnected(Instant.now()), syncState, createdAt, Instant.now());
    }

    public String configurationValue(String key) {
        return configuration.get(key);
    }

    // Flat accessors preserved for API/persistence mapping; they delegate to
    // the two value objects rather than duplicating state.

    public ConnectionState connectionState() {
        return connection.state();
    }

    public ConnectorHealth health() {
        return connection.health();
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    public Instant lastAttemptedSyncAt() {
        return syncState.lastAttemptedAt();
    }

    public Instant lastSuccessfulSyncAt() {
        return syncState.lastSuccessfulAt();
    }

    /** The most recent failure, connection first, then sync. */
    public String lastError() {
        return connection.lastError() != null ? connection.lastError() : syncState.lastError();
    }
}
