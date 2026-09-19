package com.rollbackshield.integrations.domain.connector;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Metadata read from a source repository. Every field is optional because a
 * provider may genuinely not have it; callers must treat null as unknown,
 * not as absence.
 */
public record RepositoryInspection(
    String externalId,
    String defaultBranch,
    String headCommitSha,
    String headCommitMessage,
    Instant headCommitAt,
    List<String> tags,
    boolean hasDockerfile,
    List<String> dockerfilePaths,
    List<String> migrationFilePaths,
    List<String> liquibaseChangelogPaths,
    List<String> deploymentFilePaths,
    List<String> recentCommitShas
) {

    public RepositoryInspection {
        Objects.requireNonNull(externalId, "externalId");
        tags = List.copyOf(tags == null ? List.of() : tags);
        dockerfilePaths = List.copyOf(dockerfilePaths == null ? List.of() : dockerfilePaths);
        migrationFilePaths = List.copyOf(migrationFilePaths == null ? List.of() : migrationFilePaths);
        liquibaseChangelogPaths = List.copyOf(
            liquibaseChangelogPaths == null ? List.of() : liquibaseChangelogPaths);
        deploymentFilePaths = List.copyOf(deploymentFilePaths == null ? List.of() : deploymentFilePaths);
        recentCommitShas = List.copyOf(recentCommitShas == null ? List.of() : recentCommitShas);
    }

    /** Flyway-style SQL migrations, which the deterministic analyser can classify. */
    public boolean hasMigrationFiles() {
        return !migrationFilePaths.isEmpty();
    }

    /** Liquibase changelogs are detected for mapping, but not SQL-analyzed in this build. */
    public boolean hasLiquibaseChangelogs() {
        return !liquibaseChangelogPaths.isEmpty();
    }

    public boolean hasAnyMigrationSource() {
        return hasMigrationFiles() || hasLiquibaseChangelogs();
    }
}
