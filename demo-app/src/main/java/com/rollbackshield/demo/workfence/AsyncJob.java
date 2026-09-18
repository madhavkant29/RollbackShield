package com.rollbackshield.demo.workfence;

import java.time.Instant;
import java.util.Objects;

public record AsyncJob(
    String jobId,
    String releaseId,
    long releaseEpoch,
    String jobType,
    Instant createdAt,
    String payload
) {
    public AsyncJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
