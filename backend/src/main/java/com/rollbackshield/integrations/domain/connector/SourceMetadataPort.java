package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.MigrationFile;

import java.util.List;

/** Reads repository metadata and file contents for a source repository. */
public interface SourceMetadataPort {

    RepositoryInspection inspectSource(ConnectorContext context, String repositoryExternalId);

    /** Fetches migration file contents. Empty if the path list is empty. */
    List<MigrationFile> fetchMigrationFiles(ConnectorContext context, String repositoryExternalId,
                                            List<String> paths);

    /** Full text of a single file, or null if it does not exist. */
    String fetchFile(ConnectorContext context, String repositoryExternalId, String path);

    /**
     * Migration files changed between two commits. Used to isolate the
     * migrations a candidate deployment introduced, instead of guessing from
     * versions. The adapter filters to migration paths; the application layer
     * never pattern-matches repository layouts.
     */
    java.util.List<String> changedMigrationFilesBetween(ConnectorContext context,
                                                        String repositoryExternalId,
                                                        String baseSha, String headSha);
}
