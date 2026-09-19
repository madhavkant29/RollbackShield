package com.rollbackshield.connectors.postgres.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseDiscoveryPort;
import com.rollbackshield.integrations.domain.connector.DatabaseObject;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL: schema observation for rollback compatibility evidence. This is
 * deliberately read-only -- RollbackShield is not a migration tool and never
 * writes to a customer's database. Only information_schema is queried.
 */
@Component
public class PostgresDatabaseAdapter implements CapabilityProvider, DiscoveryContributionPort,
    DatabaseDiscoveryPort {

    private static final int MAX_TABLES = 200;

    private final PostgresConnection connection;

    public PostgresDatabaseAdapter(PostgresConnection connection) {
        this.connection = connection;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.POSTGRESQL;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.DATABASE_DISCOVERY);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        List<DiscoveredResource> found = new ArrayList<>(discoverDatabases(context));
        try (Connection jdbc = connection.open(context)) {
            for (DatabaseObject object : connection.schemaObjects(jdbc, MAX_TABLES)) {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("schema", object.schema());
                metadata.put("kind", object.kind());
                metadata.put("columnCount", String.valueOf(object.columns().size()));
                found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.POSTGRESQL,
                    DiscoveredResourceType.DATABASE_TABLE,
                    object.schema() + "." + object.name(), object.name(),
                    context.config("region", null), metadata));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL discovery failed: " + e.getMessage(), e);
        }
        return found;
    }

    @Override
    public List<DiscoveredResource> discoverDatabases(ConnectorContext context) {
        try (Connection jdbc = connection.open(context)) {
            Map<String, String> summary = connection.databaseSummary(jdbc);
            String database = summary.getOrDefault("database", context.endpoint());
            Map<String, String> metadata = new LinkedHashMap<>(summary);
            metadata.put("jdbcUrl", context.config("jdbcUrl", context.endpoint()));
            return List.of(DiscoveredResource.of(context.integration().id(), ConnectorType.POSTGRESQL,
                DiscoveredResourceType.DATABASE, database, database,
                context.config("region", null), metadata));
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL discovery failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<DatabaseObject> discoverSchemaObjects(ConnectorContext context,
                                                      String databaseExternalId) {
        try (Connection jdbc = connection.open(context)) {
            return connection.schemaObjects(jdbc, MAX_TABLES);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL schema inspection failed: " + e.getMessage(), e);
        }
    }
}
