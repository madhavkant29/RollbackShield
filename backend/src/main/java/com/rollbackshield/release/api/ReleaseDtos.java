package com.rollbackshield.release.api;

import jakarta.validation.constraints.NotBlank;

public final class ReleaseDtos {

    private ReleaseDtos() {
    }

    public record CreateReleaseRequest(@NotBlank String serviceId, @NotBlank String previousVersionLabel,
                                        @NotBlank String candidateVersionLabel) {
    }

    public record RollbackRequest(String reason) {
    }

    public record ReleaseResponse(String releaseId, String organizationId, String serviceId,
                                   String previousVersionLabel, String candidateVersionLabel,
                                   String state, long epoch) {
    }
}
