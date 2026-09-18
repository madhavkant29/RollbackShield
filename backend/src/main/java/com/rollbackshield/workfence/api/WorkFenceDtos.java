package com.rollbackshield.workfence.api;

import jakarta.validation.constraints.NotBlank;

public final class WorkFenceDtos {

    private WorkFenceDtos() {
    }

    public record EnqueueWorkRequest(@NotBlank String jobType, String payload) {
    }

    public record WorkJobResponse(String jobId, String releaseId, long releaseEpoch, String jobType,
                                   String payload) {
    }

    public record RedeemRequest(@NotBlank String releaseId, long releaseEpoch) {
    }

    public record RedeemResponse(String jobId, String outcome) {
    }
}
