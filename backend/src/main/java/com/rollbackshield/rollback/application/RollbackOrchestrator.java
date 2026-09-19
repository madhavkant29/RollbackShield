package com.rollbackshield.rollback.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.RollbackTarget;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.HealthVerificationPort;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionPort;
import com.rollbackshield.integrations.domain.connector.RollbackExecutionResult;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.reversibility.application.ArtifactAvailabilityChecker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes a rollback as a sequence of real steps against the connected
 * runtime: authorize (caller tenant check + release transition), invalidate
 * the candidate work epoch, verify the rollback artifact still exists,
 * request the runtime restore, monitor convergence, verify health, then mark
 * ROLLED_BACK. If any step fails, the release is marked FAILED with the
 * exact step and provider message -- ROLLED_BACK is never displayed for a
 * rollback that did not converge. Retried invocations are safe: requesting
 * the revision the runtime already runs completes as a no-op.
 */
@Service
public class RollbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RollbackOrchestrator.class);

    private final ReleaseApplicationService releases;
    private final CatalogApplicationService catalog;
    private final ServiceMappingRepository mappings;
    private final IntegrationApplicationService integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final ArtifactAvailabilityChecker artifacts;
    private final RollbackMonitorPolicy monitorPolicy;
    private final AuditTrail auditTrail;
    private final EventPublisher events;

    public RollbackOrchestrator(ReleaseApplicationService releases, CatalogApplicationService catalog,
                                ServiceMappingRepository mappings,
                                IntegrationApplicationService integrations,
                                ConnectorRegistry connectors, CredentialResolver credentials,
                                ArtifactAvailabilityChecker artifacts,
                                RollbackMonitorPolicy monitorPolicy,
                                AuditTrail auditTrail, EventPublisher events) {
        this.releases = releases;
        this.catalog = catalog;
        this.mappings = mappings;
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
        this.artifacts = artifacts;
        this.monitorPolicy = monitorPolicy;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    public Release rollback(OrganizationId callerOrganizationId, ReleaseId releaseId, String reason) {
        // Authorize: a release that is not the caller's organization is a 404.
        Release release = releases.get(releaseId, callerOrganizationId);
        catalog.getService(release.serviceId(), callerOrganizationId);

        Release rollingBack = releases.beginRollback(releaseId, reason);
        releases.invalidateEpoch(rollingBack);
        events.publish(DomainEvent.of("RollbackExecutionStarted", callerOrganizationId.toString(),
            releaseId.toString(), Map.of("reason", reason)));

        ServiceMapping mapping = mappings.findByService(rollingBack.serviceId()).orElse(null);
        Optional<ResourceBinding> runtimeBinding = mapping == null
            ? Optional.empty() : mapping.firstBinding(ResourceBinding.BindingRole.RUNTIME);

        if (runtimeBinding.isEmpty()) {
            // No connected runtime: the rollback is an honest control-plane
            // state change, recorded as such -- not a simulated provider call.
            return releases.completeRollback(releaseId,
                "no runtime connector is bound; rollback recorded without provider execution");
        }

        ResourceBinding binding = runtimeBinding.get();
        Integration integration = integrations.get(binding.integrationId(), rollingBack.organizationId());
        ConnectorContext context = new ConnectorContext(integration,
            credentials.resolve(integration.credential()));

        DeploymentObservationPort observationPort = connectors.port(integration.type(),
            ConnectorCapability.DEPLOYMENT_STATUS, DeploymentObservationPort.class);
        DeploymentIdentity identity;
        try {
            identity = observationPort.observeDeployment(context, binding.externalId())
                .orElseThrow(() -> new IllegalStateException(
                    "runtime " + binding.externalId() + " was not found"));
        } catch (RuntimeException e) {
            return fail(rollingBack.organizationId(), releaseId, "ecs:DescribeServices", "runtime could not be observed before rollback: "
                + safeMessage(e));
        }
        if (!identity.hasPreviousRevision()) {
            return fail(rollingBack.organizationId(), releaseId, "verify-target",
                "runtime reports no previous revision to return to");
        }

        List<com.rollbackshield.reversibility.domain.ReversibilityEvidence> artifactEvidence =
            new ArrayList<>();
        ReversibilityCheck artifactCheck = artifacts.check(release, mapping, identity, artifactEvidence);
        if (!artifactCheck.passed()) {
            return fail(rollingBack.organizationId(), releaseId, "verify-artifact", artifactCheck.blocker().description());
        }

        RollbackTarget target = new RollbackTarget(identity.runtimeExternalId(), identity.runtimeName(),
            identity.previousRevision(), identity.previousArtifactDigest(), identity.commitSha());
        RollbackExecutionPort executionPort;
        try {
            executionPort = connectors.port(integration.type(),
                ConnectorCapability.ROLLBACK_EXECUTION, RollbackExecutionPort.class);
        } catch (RuntimeException e) {
            return fail(rollingBack.organizationId(), releaseId, "resolve-rollback-executor",
                "runtime connector cannot execute rollbacks: " + safeMessage(e));
        }

        RollbackExecutionResult request;
        try {
            request = executionPort.requestRollback(context, target);
        } catch (RuntimeException e) {
            return fail(rollingBack.organizationId(), releaseId, "request-rollback", safeMessage(e));
        }
        audit(rollingBack.organizationId(), releaseId, "request-rollback", request);
        if (request.isFailed()) {
            return fail(rollingBack.organizationId(), releaseId, request.step(), request.detail());
        }

        RollbackExecutionResult monitor = request;
        for (int attempt = 0; monitor.state() == RollbackExecutionResult.State.IN_PROGRESS
            && attempt < monitorPolicy.maxAttempts(); attempt++) {
            sleep(monitorPolicy.interval());
            try {
                monitor = executionPort.monitorRollback(context, target);
            } catch (RuntimeException e) {
                return fail(rollingBack.organizationId(), releaseId, "monitor-rollback", safeMessage(e));
            }
            audit(rollingBack.organizationId(), releaseId, "monitor-rollback", monitor);
            if (monitor.isFailed()) {
                return fail(rollingBack.organizationId(), releaseId, monitor.step(), monitor.detail());
            }
        }
        if (monitor.state() != RollbackExecutionResult.State.COMPLETED) {
            return fail(rollingBack.organizationId(), releaseId, "monitor-rollback",
                "runtime did not converge within " + monitorPolicy.timeout().toSeconds() + "s: "
                    + monitor.detail());
        }

        try {
            HealthVerificationPort healthPort = connectors.port(integration.type(),
                ConnectorCapability.HEALTH_VERIFICATION, HealthVerificationPort.class);
            HealthObservation health = healthPort.verifyHealth(context, binding.externalId());
            audit(rollingBack.organizationId(), releaseId, "verify-health", new RollbackExecutionResult("verify-health",
                health.state() == HealthObservation.HealthState.UNHEALTHY
                    ? RollbackExecutionResult.State.FAILED : RollbackExecutionResult.State.COMPLETED,
                health.detail(), health.metrics()));
            if (health.state() == HealthObservation.HealthState.UNHEALTHY) {
                return fail(rollingBack.organizationId(), releaseId, "verify-health",
                    "rollback converged but the service is unhealthy: " + health.detail());
            }
            return releases.completeRollback(releaseId,
                "restored " + DeploymentIdentity.shortRevision(target.targetRevision())
                    + "; health " + health.state() + " (" + health.detail() + ")");
        } catch (ConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            return fail(rollingBack.organizationId(), releaseId, "verify-health", safeMessage(e));
        }
    }

    /** Records the failed step and marks the release FAILED; never ROLLED_BACK. */
    private Release fail(OrganizationId organizationId, ReleaseId releaseId, String step, String detail) {
        String summary = step + ": " + detail;
        auditTrail.append(AuditEvent.of(organizationId.toString(), releaseId.toString(), "system",
            AuditAction.ROLLBACK_EXECUTION_FAILED, releaseId.toString(), summary, "ROLLING_BACK", "FAILED",
            Map.of("step", step)));
        log.warn("rollback failed release={} step={} detail={}", releaseId, step, detail);
        return releases.failRollback(releaseId, summary);
    }

    private void audit(OrganizationId organizationId, ReleaseId releaseId, String step, RollbackExecutionResult result) {
        auditTrail.append(AuditEvent.of(organizationId.toString(), releaseId.toString(), "system",
            AuditAction.ROLLBACK_EXECUTION_STEP, releaseId.toString(),
            step + " " + result.state() + ": " + result.detail(), null, null,
            Map.of("step", step, "state", result.state().name())));
    }

    private static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rollback monitoring interrupted", e);
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

