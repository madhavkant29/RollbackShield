package com.rollbackshield.workfence.domain;

import com.rollbackshield.shared.domain.ReleaseId;

import java.time.Instant;
import java.util.Objects;

public record WorkJob(
    String jobId,
    ReleaseId releaseId,
    long releaseEpoch,
    String jobType,
    Instant createdAt,
    String payload
) {
    public WorkJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
