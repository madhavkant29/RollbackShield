package com.rollbackshield.connectors.flyway.adapter;

import com.rollbackshield.connectors.flyway.domain.MigrationAnalyzer;
import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseMigrationAnalysisPort;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flyway migration analysis. Analysis itself is pure and deterministic
 * ({@link MigrationAnalyzer}); the connector adds the ability to point at a
 * local migration directory for environments where migrations are not read
 * from source control (the GitHub connector covers that case).
 */
@Component
public class FlywayMigrationAnalyzer implements Connector, CapabilityProvider,
    DatabaseMigrationAnalysisPort {

    @Override
    public ConnectorType type() {
        return ConnectorType.FLYWAY;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.DATABASE_MIGRATION_ANALYSIS);
    }

    @Override
    public Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
        supportedCredentialKinds() {
        return Set.of(com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
            .NONE);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorContext context) {
        String directory = context.config("directory", null);
        if (directory == null || directory.isBlank()) {
            return ConnectionTestResult.failed(
                "Flyway integration requires configuration key 'directory'", Map.of());
        }
        Path path = Path.of(directory);
        if (!Files.isDirectory(path)) {
            return ConnectionTestResult.failed("Migration directory does not exist: " + directory, Map.of());
        }
        try {
            long sqlFiles = Files.walk(path, 8)
                .filter(file -> file.getFileName().toString().toLowerCase().endsWith(".sql"))
                .limit(1000)
                .count();
            return ConnectionTestResult.ok("Migration directory readable (" + sqlFiles + " SQL files)",
                Map.of("directory", directory, "sqlFiles", String.valueOf(sqlFiles)));
        } catch (java.io.IOException e) {
            return ConnectionTestResult.failed("Unable to read migration directory: " + e.getMessage(),
                Map.of("directory", directory));
        }
    }

    @Override
    public List<MigrationAnalysis> analyze(List<MigrationFile> migrations) {
        return MigrationAnalyzer.analyze(migrations);
    }
}
