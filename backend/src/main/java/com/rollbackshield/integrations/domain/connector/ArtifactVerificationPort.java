package com.rollbackshield.integrations.domain.connector;

import java.util.Optional;

/**
 * Looks up an artifact by immutable digest. Used by preflight to answer
 * "does the rollback artifact still exist?", with absence reported as empty
 * rather than an exception.
 */
public interface ArtifactVerificationPort {

    Optional<ArtifactDescriptor> findArtifactByDigest(ConnectorContext context,
                                                      String repositoryExternalId,
                                                      String digest);
}
