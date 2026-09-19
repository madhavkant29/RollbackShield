package com.rollbackshield.deployment.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.integrations.domain.DeploymentObservationRepository;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DeploymentObservationPort;
import com.rollbackshield.integrations.domain.connector.RepositoryInspection;
import com.rollbackshield.integrations.domain.connector.SourceMetadataPort;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.reversibility.application.ReversibilityApplicationService;
import com.rollbackshield.reversibility.domain.PreflightReport;
import com.rollbackshield.shared.api.ConflictException;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The automatic release workflow: observe the connected runtime, discover
 * candidate and previous revisions, resolve the source commit, then create
 * (or reuse) the release and run the first preflight. This is the normal
 * product path; the explicit release endpoints remain for local development
 * and unsupported systems.
 *
 * Repeated observations of the same candidate revision reuse the same
 * release. A changed candidate revision creates a new release -- an
 * observation is never silently folded into an older one.
 */
@Service
public class DeploymentObservationService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentObservationService.class);

    private final CatalogApplicationService catalog;
    private final ServiceMappingRepository mappings;
    private final DeploymentObservationRepository observations;
    private final IntegrationApplicationService integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final ReleaseApplicationService releases;
    private final ReversibilityApplicationService preflight;
    private final AuditTrail auditTrail;
    private final EventPublisher events;

    public DeploymentObservationService(CatalogApplicationService catalog,
                                        ServiceMappingRepository mappings,
                                        DeploymentObservationRepository observations,
                                        IntegrationApplicationService integrations,
                                        ConnectorRegistry connectors,
                                        CredentialResolver credentials,
                                        ReleaseApplicationService releases,
                                        ReversibilityApplicationService preflight,
                                        AuditTrail auditTrail,
                                        EventPublisher events) {
        this.catalog = catalog;
        this.mappings = mappings;
        this.observations = observations;
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
        this.releases = releases;
        this.preflight = preflight;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    /**
     * Observes the runtime and drives the automatic release workflow. The
     * returned observation always carries the linked release id.
     */
    public ObservedDeployment observe(OrganizationId organizationId, ServiceId serviceId) {
        catalog.getService(serviceId, organizationId);
        ServiceMapping mapping = mappings.findByService(serviceId)
            .orElseThrow(() -> new ConflictException("SERVICE_NOT_MAPPED",
                "Service " + serviceId + " has no resource mapping; import it from a connected runtime first",
                Map.of("serviceId", serviceId.toString())));
        ResourceBinding runtimeBinding = mapping.firstBinding(ResourceBinding.BindingRole.RUNTIME)
            .orElseThrow(() -> new ConflictException("RUNTIME_NOT_MAPPED",
                "Service " + serviceId + " has no runtime binding", Map.of()));

        Integration integration = integrations.get(runtimeBinding.integrationId(), organizationId);
        ConnectorContext context = new ConnectorContext(integration,
            credentials.resolve(integration.credential()));
        DeploymentObservationPort port = connectors.port(integration.type(),
            ConnectorCapability.DEPLOYMENT_STATUS, DeploymentObservationPort.class);

        DeploymentIdentity identity = port.observeDeployment(context, runtimeBinding.externalId())
            .orElseThrow(() -> new NotFoundException("DEPLOYMENT_NOT_OBSERVED",
                "Runtime " + runtimeBinding.externalId() + " was not found in " + integration.name()));

        identity = enrichWithSourceCommit(organizationId, mapping, identity);

        DeploymentObservation previous = observations.findLatestForService(serviceId).orElse(null);
        DeploymentObservation observation = DeploymentObservation.observe(organizationId, serviceId,
            integration.id(), identity);

        boolean sameCandidate = previous != null
            && previous.identity().candidateRevision().equals(identity.candidateRevision())
            && previous.releaseId() != null
            && !isTerminal(previous.releaseId(), organizationId);

        Release release;
        boolean releaseCreated;
        if (sameCandidate) {
            // No new deployment: keep the existing release and re-evaluate it.
            release = releases.get(previous.releaseId(), organizationId);
            observation = observation.linkedTo(release.id());
            releaseCreated = false;
        } else {
            release = createProtectedCandidate(organizationId, serviceId, identity);
            observation = observation.linkedTo(release.id());
            releaseCreated = true;
        }
        observations.save(observation);

        auditTrail.append(AuditEvent.of(organizationId.toString(), serviceId.toString(), "system",
            AuditAction.DEPLOYMENT_OBSERVED, serviceId.toString(),
            identity.candidateVersionLabel() + " (previous " + identity.previousVersionLabel() + ")",
            null, null, Map.of("integrationId", integration.id().toString(),
                "candidateRevision", identity.candidateRevision(),
                "previousRevision", String.valueOf(identity.previousRevision()),
                "commitSha", String.valueOf(identity.commitSha()),
                "candidateDigest", String.valueOf(identity.candidateArtifactDigest()),
                "previousDigest", String.valueOf(identity.previousArtifactDigest()),
                "releaseCreated", String.valueOf(releaseCreated))));
        events.publish(DomainEvent.of("DeploymentObserved", organizationId.toString(),
            serviceId.toString(), Map.of("candidateRevision", identity.candidateRevision(),
                "previousRevision", String.valueOf(identity.previousRevision()),
                "releaseId", release.id().toString(),
                "releaseCreated", String.valueOf(releaseCreated))));

        runPreflight(organizationId, release);
        return new ObservedDeployment(observation, release, releaseCreated);
    }

    /**
     * Creates the release for an observed deployment and advances it to
     * READY, which is the point where a rollback contract may be activated.
     * The deployment already happened; the operator's next decision is
     * protect-or-commit, not "prepare".
     */
    private Release createProtectedCandidate(OrganizationId organizationId, ServiceId serviceId,
                                             DeploymentIdentity identity) {
        Release release = releases.create(organizationId, serviceId,
            identity.previousVersionLabel(), identity.candidateVersionLabel());
        release = releases.prepare(release.id());
        release = releases.markReady(release.id());
        auditTrail.append(AuditEvent.of(organizationId.toString(), release.id().toString(), "system",
            AuditAction.RELEASE_OBSERVED, release.id().toString(),
            identity.candidateVersionLabel() + " observed from " + identity.runtimeName()
                + " (auto-created, advanced to READY)",
            "DRAFT", release.state().name(),
            Map.of("auto", "true", "candidateRevision", identity.candidateRevision(),
                "previousRevision", String.valueOf(identity.previousRevision()))));
        events.publish(DomainEvent.of("ReleaseObserved", organizationId.toString(),
            release.id().toString(), Map.of("auto", "true",
                "candidateRevision", identity.candidateRevision())));
        return release;
    }

    /**
     * A closed release (rolled back, committed, failed, cancelled) must not be
     * reused for a new observation: the deployment is being re-observed as a
     * fresh candidate decision, so a new release is created.
     */
    private boolean isTerminal(com.rollbackshield.shared.domain.ReleaseId releaseId,
                               OrganizationId organizationId) {
        try {
            var state = releases.get(releaseId, organizationId).state();
            return state == com.rollbackshield.release.domain.ReleaseState.ROLLED_BACK
                || state == com.rollbackshield.release.domain.ReleaseState.COMMITTED
                || state == com.rollbackshield.release.domain.ReleaseState.FAILED
                || state == com.rollbackshield.release.domain.ReleaseState.CANCELLED;
        } catch (RuntimeException e) {
            return true; // unknown release: do not reuse
        }
    }

    /** First preflight after observation; a failure is recorded, never fatal to the observation. */
    private void runPreflight(OrganizationId organizationId, Release release) {
        try {
            PreflightReport report = preflight.evaluate(release.id());
            auditTrail.append(AuditEvent.of(organizationId.toString(), release.id().toString(), "system",
                AuditAction.RELEASE_PREFLIGHT_EVALUATED, release.id().toString(),
                report.status() + "/" + report.verdict(), null, null,
                Map.of("status", report.status().name(), "verdict", report.verdict().name(),
                    "blockers", String.valueOf(report.blockers().size()))));
            events.publish(DomainEvent.of("ReleasePreflightEvaluated", organizationId.toString(),
                release.id().toString(), Map.of("status", report.status().name(),
                    "verdict", report.verdict().name())));
        } catch (RuntimeException e) {
            log.warn("preflight after observation failed release={} error={}", release.id(), e.getMessage());
            auditTrail.append(AuditEvent.of(organizationId.toString(), release.id().toString(), "system",
                AuditAction.RELEASE_PREFLIGHT_EVALUATED, release.id().toString(),
                "evaluation_error: " + e.getMessage(), null, null, Map.of("error", "true")));
        }
    }

    /** Explicit fallback: returns the already-created release for the latest observation. */
    public ObservedRelease createRelease(OrganizationId organizationId, ServiceId serviceId) {
        catalog.getService(serviceId, organizationId);
        DeploymentObservation observation = observations.findLatestForService(serviceId)
            .orElseThrow(() -> new ConflictException("DEPLOYMENT_NOT_OBSERVED",
                "No deployment has been observed for this service yet; observe before creating a release",
                Map.of("serviceId", serviceId.toString())));
        if (observation.releaseId() != null) {
            Release existing = releases.get(observation.releaseId(), organizationId);
            return new ObservedRelease(observation, existing);
        }

        // Only reachable for observations recorded before automatic creation
        // existed, or created out-of-band; create and link one now.
        DeploymentIdentity identity = observation.identity();
        Release release = releases.create(organizationId, serviceId,
            identity.previousVersionLabel(), identity.candidateVersionLabel());
        DeploymentObservation linked = observations.save(observation.linkedTo(release.id()));
        auditTrail.append(AuditEvent.of(organizationId.toString(), release.id().toString(), "system",
            AuditAction.RELEASE_OBSERVED, release.id().toString(),
            identity.candidateVersionLabel() + " observed from " + identity.runtimeName(),
            null, "DRAFT", Map.of("observationId", linked.id().toString())));
        events.publish(DomainEvent.of("ReleaseObserved", organizationId.toString(),
            release.id().toString(), Map.of("observationId", linked.id().toString(),
                "candidateRevision", identity.candidateRevision())));
        return new ObservedRelease(linked, release);
    }

    public List<DeploymentObservation> list(OrganizationId organizationId, ServiceId serviceId) {
        catalog.getService(serviceId, organizationId);
        return observations.findByService(serviceId);
    }

    public DeploymentObservation latest(OrganizationId organizationId, ServiceId serviceId) {
        catalog.getService(serviceId, organizationId);
        return observations.findLatestForService(serviceId)
            .orElseThrow(() -> new NotFoundException("DEPLOYMENT_NOT_OBSERVED",
                "No deployment has been observed for this service yet"));
    }

    /**
     * Records which commit the runtime runs, using only HIGH-confidence
     * repository evidence. A MEDIUM binding ("name matches; confirm this
     * mapping") is deliberately not trusted for decisions until an operator
     * confirms it.
     */
    private DeploymentIdentity enrichWithSourceCommit(OrganizationId organizationId,
                                                      ServiceMapping mapping,
                                                      DeploymentIdentity identity) {
        return mapping.firstBinding(ResourceBinding.BindingRole.REPOSITORY)
            .filter(binding -> binding.confidence() == ResourceBinding.MappingConfidence.HIGH)
            .map(binding -> {
                try {
                    Integration integration = integrations.get(binding.integrationId(), organizationId);
                    SourceMetadataPort sourcePort = connectors.port(integration.type(),
                        ConnectorCapability.SOURCE_METADATA, SourceMetadataPort.class);
                    ConnectorContext context = new ConnectorContext(integration,
                        credentials.resolve(integration.credential()));
                    RepositoryInspection inspection = sourcePort.inspectSource(context,
                        binding.externalId());
                    return new DeploymentIdentity(identity.runtimeName(), identity.runtimeExternalId(),
                        identity.candidateRevision(), identity.previousRevision(),
                        identity.candidateArtifactDigest(), identity.previousArtifactDigest(),
                        inspection.headCommitSha(), inspection.defaultBranch(),
                        identity.artifactRepository(), identity.deploymentStatus());
                } catch (RuntimeException e) {
                    // Source metadata is enrichment; its absence must not
                    // fabricate a commit. Preflight will report UNKNOWN.
                    return identity;
                }
            })
            .orElse(identity);
    }

    public record ObservedRelease(DeploymentObservation observation, Release release) {
    }

    /** Result of an automatic observation: the observation and its release. */
    public record ObservedDeployment(DeploymentObservation observation, Release release,
                                     boolean releaseCreated) {
    }
}
