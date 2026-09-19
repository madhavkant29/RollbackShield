package com.rollbackshield.reversibility.application;

import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.RollbackTarget;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.HealthObservation;
import com.rollbackshield.integrations.domain.connector.HealthVerificationPort;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.reversibility.domain.ReversibilityBlocker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvidence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes the COMPUTE, ARTIFACT and HEALTH dimensions of preflight from the
 * connected runtime: can the previous runtime revision be restored, does the
 * previous immutable artifact still exist, and can health be verified. Every
 * failure names the exact provider fact that failed.
 */
@Service
public class RuntimeCompatibilityService {

    private final IntegrationApplicationService integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final ArtifactAvailabilityChecker artifacts;

    public RuntimeCompatibilityService(IntegrationApplicationService integrations,
                                       ConnectorRegistry connectors, CredentialResolver credentials,
                                       ArtifactAvailabilityChecker artifacts) {
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
        this.artifacts = artifacts;
    }

    public RuntimeCompatibility evaluate(Release release, ServiceMapping mapping) {
        List<ReversibilityCheck> checks = new ArrayList<>();
        List<ReversibilityEvidence> evidence = new ArrayList<>();
        String candidateLabel = release.candidateVersionLabel();

        Optional<ResourceBinding> runtimeBinding = mapping.firstBinding(ResourceBinding.BindingRole.RUNTIME);
        if (runtimeBinding.isEmpty()) {
            checks.add(ReversibilityCheck.fail("Compute restore path",
                ReversibilityBlocker.unknown(ReversibilityBlocker.NO_RUNTIME_MAPPING,
                    "no runtime is mapped to this service, so no provider rollback path exists "
                        + "(rollback would be a control-plane state change only)")));
            evidence.add(new ReversibilityEvidence(release.serviceId().toString(), "has-no",
                "runtime binding", "service-mapping", "map an ECS service or Kubernetes deployment first"));
            return new RuntimeCompatibility(checks, evidence, null);
        }

        ResourceBinding binding = runtimeBinding.get();
        Integration runtimeIntegration = integrations.get(binding.integrationId(),
            release.organizationId());
        ConnectorContext context = new ConnectorContext(runtimeIntegration,
            credentials.resolve(runtimeIntegration.credential()));

        DeploymentObservationPort observationPort = connectors.port(runtimeIntegration.type(),
            ConnectorCapability.DEPLOYMENT_STATUS, DeploymentObservationPort.class);

        Optional<DeploymentIdentity> observed;
        try {
            observed = observationPort.observeDeployment(context, binding.externalId());
        } catch (RuntimeException e) {
            checks.add(ReversibilityCheck.fail("Compute restore path",
                ReversibilityBlocker.unknown(ReversibilityBlocker.RUNTIME_NOT_OBSERVABLE,
                    "runtime could not be observed: " + safeMessage(e))));
            return new RuntimeCompatibility(checks, evidence, null);
        }

        if (observed.isEmpty()) {
            checks.add(ReversibilityCheck.fail("Compute restore path",
                ReversibilityBlocker.unknown(ReversibilityBlocker.RUNTIME_NOT_OBSERVABLE,
                    "runtime " + binding.externalId() + " was not found in "
                        + runtimeIntegration.name())));
            return new RuntimeCompatibility(checks, evidence, null);
        }

        DeploymentIdentity identity = observed.get();
        evidence.add(new ReversibilityEvidence(candidateLabel, "runs-as",
            DeploymentIdentity.shortRevision(identity.candidateRevision()),
            source(runtimeIntegration), "observed via " + runtimeIntegration.type()));
        if (identity.hasPreviousRevision()) {
            evidence.add(new ReversibilityEvidence(candidateLabel, "rollback-target",
                DeploymentIdentity.shortRevision(identity.previousRevision()),
                source(runtimeIntegration), "previous revision reported by the runtime"));
            checks.add(ReversibilityCheck.pass("Compute restore path"));
        } else {
            checks.add(ReversibilityCheck.fail("Compute restore path",
                ReversibilityBlocker.blocking(ReversibilityBlocker.NO_ROLLBACK_TARGET,
                    "runtime reports no previous revision to return to; there is nothing to roll back to")));
        }

        checks.add(artifacts.check(release, mapping, identity, evidence));
        checks.add(healthCheck(mapping, identity, evidence));
        checks.add(rollbackExecutionCheck(mapping, identity, evidence));
        return new RuntimeCompatibility(checks, evidence, identity);
    }

    /**
     * A connector can identify the rollback target but not be able to execute
     * a rollback (Kubernetes today). That is reported as UNKNOWN -- the
     * release is not claimed reversible just because the target is known.
     */
    private ReversibilityCheck rollbackExecutionCheck(ServiceMapping mapping, DeploymentIdentity identity,
                                                      List<ReversibilityEvidence> evidence) {
        Optional<ResourceBinding> runtimeBinding = mapping.firstBinding(ResourceBinding.BindingRole.RUNTIME);
        if (runtimeBinding.isEmpty()) {
            return ReversibilityCheck.fail("Rollback execution",
                ReversibilityBlocker.unknown(ReversibilityBlocker.ROLLBACK_EXECUTION_UNSUPPORTED,
                    "no runtime is mapped, so no rollback can be executed"));
        }
        ResourceBinding binding = runtimeBinding.get();
        Integration integration = integrations.get(binding.integrationId(), mapping.organizationId());
        if (connectors.supportedCapabilities(integration.type())
            .contains(ConnectorCapability.ROLLBACK_EXECUTION)) {
            return ReversibilityCheck.pass("Rollback execution");
        }
        evidence.add(new ReversibilityEvidence(identity.runtimeName(), "rollback-execution",
            "unsupported", source(integration),
            "connector can identify the rollback target but has no rollback execution capability; "
                + "no provider call will be attempted"));
        return ReversibilityCheck.fail("Rollback execution",
            ReversibilityBlocker.unknown(ReversibilityBlocker.ROLLBACK_EXECUTION_UNSUPPORTED,
                integration.type() + " can identify the rollback target but cannot execute a rollback "
                    + "in this build; the release is not claimed reversible"));
    }

    private ReversibilityCheck healthCheck(ServiceMapping mapping, DeploymentIdentity identity,
                                           List<ReversibilityEvidence> evidence) {
        Optional<ResourceBinding> runtimeBinding = mapping.firstBinding(ResourceBinding.BindingRole.RUNTIME);
        if (runtimeBinding.isEmpty()) {
            return ReversibilityCheck.fail("Runtime health observability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.HEALTH_VERIFICATION_UNAVAILABLE,
                    "no runtime is mapped"));
        }
        ResourceBinding binding = runtimeBinding.get();
        Integration integration = integrations.get(binding.integrationId(),
            mapping.organizationId());
        try {
            HealthVerificationPort port = connectors.port(integration.type(),
                ConnectorCapability.HEALTH_VERIFICATION, HealthVerificationPort.class);
            HealthObservation observation = port.verifyHealth(new ConnectorContext(integration,
                credentials.resolve(integration.credential())), binding.externalId());
            evidence.add(new ReversibilityEvidence(identity.runtimeName(), "health",
                observation.state().name(), source(integration), observation.detail()));
            return ReversibilityCheck.pass("Runtime health observability");
        } catch (RuntimeException e) {
            return ReversibilityCheck.fail("Runtime health observability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.HEALTH_VERIFICATION_UNAVAILABLE,
                    "health cannot be verified for this runtime: " + safeMessage(e)));
        }
    }

    private static String source(Integration integration) {
        return integration.type().name().toLowerCase() + ":" + integration.name();
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** Result of the runtime dimension: checks, evidence, and what was observed. */
    public record RuntimeCompatibility(List<ReversibilityCheck> checks,
                                       List<ReversibilityEvidence> evidence,
                                       DeploymentIdentity identity) {
    }

    public RollbackTarget targetFor(DeploymentIdentity identity) {
        return new RollbackTarget(identity.runtimeExternalId(), identity.runtimeName(),
            identity.previousRevision(), identity.previousArtifactDigest(), identity.commitSha());
    }
}
