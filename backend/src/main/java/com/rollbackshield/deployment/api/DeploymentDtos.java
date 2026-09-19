package com.rollbackshield.deployment.api;

import java.util.List;

public final class DeploymentDtos {

    private DeploymentDtos() {
    }

    public record DeploymentObservationResponse(
        String observationId,
        String serviceId,
        String integrationId,
        String runtimeName,
        String candidateRevision,
        String previousRevision,
        String candidateArtifactDigest,
        String previousArtifactDigest,
        String commitSha,
        String branch,
        String artifactRepository,
        String deploymentStatus,
        String releaseId,
        String releaseState,
        boolean releaseCreated,
        String observedAt
    ) {
    }

    public record CreateReleaseFromObservationResponse(
        String releaseId,
        String observationId,
        String previousVersionLabel,
        String candidateVersionLabel,
        String state
    ) {
    }

    public record ObservationListResponse(List<DeploymentObservationResponse> observations) {
    }
}
