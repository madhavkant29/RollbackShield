package com.rollbackshield.integrations.domain.connector;

import java.time.Instant;
import java.util.List;

/**
 * Immutable artifact identity, keyed by digest rather than tag. Tags are
 * listed for human context only; existence is decided on the digest.
 */
public record ArtifactDescriptor(
    String repository,
    String digest,
    List<String> tags,
    Instant pushedAt,
    long sizeBytes
) {

    public ArtifactDescriptor {
        tags = List.copyOf(tags == null ? List.of() : tags);
    }
}
