package com.rollbackshield.connectors.postgres.adapter;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL family connector: connection test opens a real JDBC connection
 * and reads the database identity. Read-only; this class contributes only
 * the connection test, the schema discovery lives in PostgresDatabaseAdapter.
 */
@Component
public class PostgresConnector implements Connector {

    private final PostgresConnection connection;

    public PostgresConnector(PostgresConnection connection) {
        this.connection = connection;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.POSTGRESQL;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
        supportedCredentialKinds() {
        return Set.of(com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
            .POSTGRES_PASSWORD);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorContext context) {
        try (Connection jdbc = connection.open(context)) {
            Map<String, String> summary = connection.databaseSummary(jdbc);
            return ConnectionTestResult.ok("PostgreSQL reachable; database "
                + summary.getOrDefault("database", "unknown"), summary);
        } catch (java.sql.SQLException e) {
            return ConnectionTestResult.failed("PostgreSQL connection failed: " + e.getMessage(),
                Map.of("jdbcUrl", String.valueOf(context.config("jdbcUrl", context.endpoint()))));
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("PostgreSQL connection failed: " + e.getMessage(),
                Map.of("jdbcUrl", String.valueOf(context.config("jdbcUrl", context.endpoint()))));
        }
    }
}
