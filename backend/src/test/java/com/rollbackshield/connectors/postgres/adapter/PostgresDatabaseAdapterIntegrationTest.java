package com.rollbackshield.connectors.postgres.adapter;

import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseObject;
import com.rollbackshield.shared.domain.OrganizationId;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the PostgreSQL connector against a real PostgreSQL server
 * (Testcontainers), including a column that a destructive migration would
 * remove -- the exact evidence preflight needs to answer "does the rollback
 * target still have the columns it reads?".
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDatabaseAdapterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("payments").withUsername("rollbackshield").withPassword("secret");

    @Test
    void discoversDatabaseAndSchemaObjectsWithColumns() throws Exception {
        try (Connection setup = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = setup.createStatement()) {
            statement.execute("create table customers (id uuid primary key, "
                + "billing_address text, region varchar(64) not null)");
            statement.execute("create view active_customers as select * from customers");
        }

        ConnectorContext context = context();
        PostgresDatabaseAdapter adapter = new PostgresDatabaseAdapter(new PostgresConnection());

        List<DiscoveredResource> discovered = adapter.discover(context);
        assertThat(discovered)
            .anyMatch(resource -> resource.resourceType() == DiscoveredResourceType.DATABASE
                && resource.externalId().equals("payments"));
        assertThat(discovered)
            .anyMatch(resource -> resource.resourceType() == DiscoveredResourceType.DATABASE_TABLE
                && resource.externalId().equals("public.customers")
                && resource.metadata().get("columnCount").equals("3"));

        List<DatabaseObject> objects = adapter.discoverSchemaObjects(context, "payments");
        DatabaseObject customers = objects.stream()
            .filter(object -> object.name().equals("customers")).findFirst().orElseThrow();
        assertThat(customers.schema()).isEqualTo("public");
        assertThat(customers.hasColumn("billing_address")).isTrue();
        assertThat(customers.columns())
            .anyMatch(column -> column.name().equals("region") && !column.nullable());
        assertThat(objects)
            .anyMatch(object -> object.name().equals("active_customers") && object.kind().contains("VIEW"));
    }

    private static ConnectorContext context() {
        Integration integration = Integration.pending(OrganizationId.newId(), "payments-db",
            ConnectorType.POSTGRESQL, POSTGRES.getJdbcUrl(),
            IntegrationCredentialReference.secret(
                IntegrationCredentialReference.CredentialKind.POSTGRES_PASSWORD, "unused-in-test"),
            Map.of("username", POSTGRES.getUsername(), "jdbcUrl", POSTGRES.getJdbcUrl()));
        return new ConnectorContext(integration,
            new CredentialMaterial.DatabasePassword(POSTGRES.getPassword()));
    }
}
