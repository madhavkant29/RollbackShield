package com.rollbackshield.deployment.api;

import com.rollbackshield.deployment.application.DeploymentObservationService;
import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.rollbackshield.deployment.api.DeploymentDtos.*;

/**
 * Deployment observation API: the flow that makes releases real. Observe the
 * connected runtime now, list what was observed, and create the release from
 * the latest observation (idempotent).
 */
@RestController
@RequestMapping("/api/v1/services")
public class DeploymentController {

    private final DeploymentObservationService observations;

    public DeploymentController(DeploymentObservationService observations) {
        this.observations = observations;
    }

    @PostMapping("/{serviceId}/observations")
    public ResponseEntity<DeploymentObservationResponse> observe(@PathVariable String serviceId) {
        DeploymentObservationService.ObservedDeployment observed =
            observations.observe(callerOrg(), ServiceId.of(serviceId));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(observed));
    }

    @GetMapping("/{serviceId}/observations")
    public ObservationListResponse list(@PathVariable String serviceId) {
        List<DeploymentObservationResponse> found = observations.list(callerOrg(), ServiceId.of(serviceId))
            .stream().map(DeploymentController::toResponse).toList();
        return new ObservationListResponse(found);
    }

    @GetMapping("/{serviceId}/observations/latest")
    public DeploymentObservationResponse latest(@PathVariable String serviceId) {
        return toResponse(observations.latest(callerOrg(), ServiceId.of(serviceId)));
    }

    @PostMapping("/{serviceId}/releases")
    public ResponseEntity<CreateReleaseFromObservationResponse> createRelease(@PathVariable String serviceId) {
        DeploymentObservationService.ObservedRelease observed = observations.createRelease(callerOrg(),
            ServiceId.of(serviceId));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateReleaseFromObservationResponse(
            observed.release().id().toString(), observed.observation().id().toString(),
            observed.release().previousVersionLabel(), observed.release().candidateVersionLabel(),
            observed.release().state().name()));
    }

    private static OrganizationId callerOrg() {
        return OrganizationId.of(CurrentPrincipal.get().organizationId());
    }

    private static DeploymentObservationResponse toResponse(
        DeploymentObservationService.ObservedDeployment observed) {
        DeploymentObservation observation = observed.observation();
        return new DeploymentObservationResponse(
            observation.id().toString(),
            observation.serviceId().toString(),
            observation.integrationId().toString(),
            observation.identity().runtimeName(),
            observation.identity().candidateRevision(),
            observation.identity().previousRevision(),
            observation.identity().candidateArtifactDigest(),
            observation.identity().previousArtifactDigest(),
            observation.identity().commitSha(),
            observation.identity().branch(),
            observation.identity().artifactRepository(),
            observation.identity().deploymentStatus(),
            observed.release().id().toString(),
            observed.release().state().name(),
            observed.releaseCreated(),
            observation.observedAt().toString());
    }

    private static DeploymentObservationResponse toResponse(DeploymentObservation observation) {
        return new DeploymentObservationResponse(
            observation.id().toString(),
            observation.serviceId().toString(),
            observation.integrationId().toString(),
            observation.identity().runtimeName(),
            observation.identity().candidateRevision(),
            observation.identity().previousRevision(),
            observation.identity().candidateArtifactDigest(),
            observation.identity().previousArtifactDigest(),
            observation.identity().commitSha(),
            observation.identity().branch(),
            observation.identity().artifactRepository(),
            observation.identity().deploymentStatus(),
            observation.releaseId() == null ? null : observation.releaseId().toString(),
            null,
            false,
            observation.observedAt().toString());
    }
}
