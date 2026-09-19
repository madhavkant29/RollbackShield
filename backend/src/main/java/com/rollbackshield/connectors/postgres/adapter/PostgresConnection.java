package com.rollbackshield.connectors.postgres.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseObject;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC transport for the PostgreSQL connector. Driver-specific concepts stay
 * inside this adapter; the connector above it deals in domain types
 * ({@link DatabaseObject}) only.
 */
@Component
public class PostgresConnection {

    private static final int QUERY_TIMEOUT_SECONDS = 15;
    private static final String EXCLUDED_SCHEMAS =
        "('pg_catalog','information_schema','pg_toast')";

    public Connection open(ConnectorContext context) throws SQLException {
        String jdbcUrl = context.config("jdbcUrl", context.endpoint());
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new SQLException("PostgreSQL integration requires a jdbcUrl endpoint "
                + "(jdbc:postgresql://host:port/database)");
        }
        String username = context.config("username", null);
        CredentialMaterial material = context.credentials();
        String password = material instanceof CredentialMaterial.DatabasePassword databasePassword
            ? databasePassword.password() : null;

        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "rollbackshield-observation");
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    public Map<String, String> databaseSummary(Connection connection) throws SQLException {
        Map<String, String> summary = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select current_database(), current_user, version()")) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    summary.put("database", result.getString(1));
                    summary.put("user", result.getString(2));
                    summary.put("version", result.getString(3));
                }
            }
        }
        return summary;
    }

    public List<DatabaseObject> schemaObjects(Connection connection, int maxTables) throws SQLException {
        Map<String, List<DatabaseObject.Column>> columns = columnsByTable(connection);
        List<DatabaseObject> objects = new ArrayList<>();
        String sql = "select table_schema, table_name, table_type from information_schema.tables "
            + "where table_schema not in " + EXCLUDED_SCHEMAS + " order by table_schema, table_name limit ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setInt(1, maxTables);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String schema = result.getString(1);
                    String name = result.getString(2);
                    String kind = result.getString(3);
                    objects.add(new DatabaseObject(schema, name, kind,
                        columns.getOrDefault(schema + "." + name, List.of())));
                }
            }
        }
        return objects;
    }

    private Map<String, List<DatabaseObject.Column>> columnsByTable(Connection connection)
        throws SQLException {
        Map<String, List<DatabaseObject.Column>> byTable = new LinkedHashMap<>();
        String sql = "select table_schema, table_name, column_name, data_type, is_nullable "
            + "from information_schema.columns where table_schema not in " + EXCLUDED_SCHEMAS
            + " order by table_schema, table_name, ordinal_position";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String key = result.getString(1) + "." + result.getString(2);
                    byTable.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new DatabaseObject.Column(result.getString(3), result.getString(4),
                            "YES".equalsIgnoreCase(result.getString(5))));
                }
            }
        }
        return byTable;
    }
}
