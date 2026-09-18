package com.rollbackshield.sdk;

import java.util.Objects;

public record ReleaseContext(
    String organizationId,
    String serviceId,
    String releaseId,
    long releaseEpoch
) {
    public ReleaseContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(releaseId, "releaseId");
    }
}
