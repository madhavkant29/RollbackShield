package com.rollbackshield.reversibility.application;

import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.connector.ArtifactDescriptor;
import com.rollbackshield.integrations.domain.connector.ArtifactVerificationPort;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.reversibility.domain.ReversibilityBlocker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvidence;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Answers one question with provider evidence: does the immutable digest the
 * previous revision was built from still exist? Shared by preflight and the
 * rollback executor so the check a user sees is exactly the check that gates
 * the rollback.
 */
@Service
public class ArtifactAvailabilityChecker {

    private final IntegrationApplicationService integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;

    public ArtifactAvailabilityChecker(IntegrationApplicationService integrations,
                                       ConnectorRegistry connectors, CredentialResolver credentials) {
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
    }

    public ReversibilityCheck check(Release release, ServiceMapping mapping, DeploymentIdentity identity,
                                    List<ReversibilityEvidence> evidence) {
        if (!identity.hasPreviousRevision()) {
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.blocking(ReversibilityBlocker.NO_ROLLBACK_TARGET,
                    "no previous revision means no artifact to verify"));
        }
        String previousDigest = identity.previousArtifactDigest();
        if (previousDigest == null) {
            evidence.add(new ReversibilityEvidence(release.candidateVersionLabel(), "artifact-unknown",
                String.valueOf(identity.previousRevision()), "runtime",
                "previous revision references a mutable tag, not a digest"));
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.ARTIFACT_IDENTITY_UNKNOWN,
                    "previous revision uses a tag or unpinned image; its immutable digest was never recorded"));
        }

        Optional<ResourceBinding> artifactBinding = mapping
            .bindingsFor(ResourceBinding.BindingRole.ARTIFACT_REPOSITORY).stream()
            .filter(binding -> identity.artifactRepository() == null
                || binding.externalId().equals(identity.artifactRepository())
                || identity.artifactRepository().endsWith("/" + binding.externalId()))
            .findFirst();
        if (artifactBinding.isEmpty()) {
            evidence.add(new ReversibilityEvidence(previousDigest, "unmapped", "artifact repository",
                "service-mapping", "no artifact repository binding matches "
                    + String.valueOf(identity.artifactRepository())));
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.ARTIFACT_REPOSITORY_NOT_MAPPED,
                    "no artifact repository is mapped for " + identity.artifactRepository()));
        }

        Integration artifactIntegration = integrations.get(artifactBinding.get().integrationId(),
            release.organizationId());
        ConnectorContext context = new ConnectorContext(artifactIntegration,
            credentials.resolve(artifactIntegration.credential()));
        ArtifactVerificationPort port;
        try {
            port = connectors.port(artifactIntegration.type(), ConnectorCapability.ARTIFACT_VERIFICATION,
                ArtifactVerificationPort.class);
        } catch (RuntimeException e) {
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.ARTIFACT_REPOSITORY_NOT_MAPPED,
                    "artifact connector cannot verify digests: " + safeMessage(e)));
        }

        String externalId = artifactBinding.get().externalId();
        String repository = externalId.contains("/")
            ? externalId.substring(externalId.lastIndexOf('/') + 1) : externalId;
        try {
            Optional<ArtifactDescriptor> artifact = port.findArtifactByDigest(context, repository,
                previousDigest);
            if (artifact.isPresent()) {
                evidence.add(new ReversibilityEvidence(previousDigest, "exists-in",
                    artifactIntegration.name(), source(artifactIntegration),
                    "digest verified in repository " + repository));
                return ReversibilityCheck.pass("Rollback artifact availability");
            }
            evidence.add(new ReversibilityEvidence(previousDigest, "missing-from",
                artifactIntegration.name(), source(artifactIntegration),
                "the rollback artifact no longer exists (repository " + repository + ")"));
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.blocking(ReversibilityBlocker.ROLLBACK_ARTIFACT_MISSING,
                    "rollback artifact " + previousDigest + " no longer exists in " + repository));
        } catch (RuntimeException e) {
            return ReversibilityCheck.fail("Rollback artifact availability",
                ReversibilityBlocker.unknown(ReversibilityBlocker.ARTIFACT_REPOSITORY_NOT_MAPPED,
                    "artifact verification failed: " + safeMessage(e)));
        }
    }

    static String source(Integration integration) {
        return integration.type().name().toLowerCase() + ":" + integration.name();
    }

    static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
