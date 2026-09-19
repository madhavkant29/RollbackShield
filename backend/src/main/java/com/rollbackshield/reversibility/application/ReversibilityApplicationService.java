package com.rollbackshield.reversibility.application;

import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.contract.domain.RollbackContractRepository;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.reversibility.domain.PreflightReport;
import com.rollbackshield.reversibility.domain.ReversibilityBlocker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvaluator;
import com.rollbackshield.reversibility.domain.ReversibilityEvidence;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.ReleaseId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the full preflight answer from the *actual current state* of the
 * release: the rollback contract, the connected runtime, the artifact
 * registry and the migration range between observed commits. Every check
 * reads real repository/connector state at request time; nothing is cached
 * or asserted. Checks are ordered per product dimension (policy, database,
 * async, compute, artifact, health) and each failure carries evidence.
 */
@Service
public class ReversibilityApplicationService {

    private final ReleaseRepository releases;
    private final RollbackContractRepository contracts;
    private final ServiceMappingRepository mappings;
    private final RuntimeCompatibilityService runtimeCompatibility;
    private final MigrationCompatibilityService migrationCompatibility;
    private final com.rollbackshield.integrations.application.IntegrationApplicationService integrations;
    private final com.rollbackshield.integrations.domain.DeploymentObservationRepository observations;

    public ReversibilityApplicationService(ReleaseRepository releases, RollbackContractRepository contracts,
                                           ServiceMappingRepository mappings,
                                           RuntimeCompatibilityService runtimeCompatibility,
                                           MigrationCompatibilityService migrationCompatibility,
                                           com.rollbackshield.integrations.application.IntegrationApplicationService integrations,
                                           com.rollbackshield.integrations.domain.DeploymentObservationRepository observations) {
        this.releases = releases;
        this.contracts = contracts;
        this.mappings = mappings;
        this.runtimeCompatibility = runtimeCompatibility;
        this.migrationCompatibility = migrationCompatibility;
        this.integrations = integrations;
        this.observations = observations;
    }

    public PreflightReport evaluate(ReleaseId releaseId) {
        Release release = releases.findById(releaseId)
            .orElseThrow(() -> new NotFoundException("RELEASE_NOT_FOUND", "No release " + releaseId));

        List<ReversibilityCheck> checks = new ArrayList<>();
        List<ReversibilityEvidence> evidence = new ArrayList<>();
        Optional<RollbackContract> activeContract = contracts.findActiveForRelease(releaseId);

        // 1. Policy / contract-derived dimensions.
        if (activeContract.isEmpty()) {
            ReversibilityBlocker blocker = new ReversibilityBlocker(ReversibilityBlocker.NO_ACTIVE_CONTRACT,
                "no active rollback contract protects this release");
            checks.add(ReversibilityCheck.fail("Data compatibility", blocker));
            checks.add(ReversibilityCheck.fail("Queued work fencing", blocker));
            checks.add(ReversibilityCheck.fail("Policy freshness", blocker));
        } else {
            checks.add(ReversibilityCheck.pass("Data compatibility"));
            RollbackContract contract = activeContract.get();
            if (contract.candidateEpochRequiredForAsyncWork()) {
                checks.add(ReversibilityCheck.pass("Queued work fencing"));
            } else {
                checks.add(ReversibilityCheck.fail("Queued work fencing",
                    new ReversibilityBlocker(ReversibilityBlocker.UNFENCED_WORK_PENDING,
                        "this contract does not require epoch fencing for async work")));
            }
            boolean windowMoot = release.state() == ReleaseState.ROLLED_BACK
                || release.state() == ReleaseState.COMMITTED;
            if (windowMoot || contract.isActive(Instant.now())) {
                checks.add(ReversibilityCheck.pass("Policy freshness"));
            } else {
                checks.add(ReversibilityCheck.fail("Policy freshness",
                    new ReversibilityBlocker(ReversibilityBlocker.POLICY_EXPIRED,
                        "rollback window has elapsed for the active contract")));
            }
            evidence.add(new ReversibilityEvidence(release.candidateVersionLabel(), "protected-by",
                "contract v" + contract.contractVersion(), "rollback-contract",
                "policy version " + contract.policyVersion()));
        }

        // 2. Database compatibility (candidate-introduced migrations).
        ServiceMapping mapping = mappings.findByService(release.serviceId())
            .orElseGet(() -> ServiceMapping.empty(release.organizationId(), release.serviceId()));
        evidence.addAll(mappedDependencyEvidence(release, mapping));
        evidence.addAll(recordedCommitEvidence(release));
        MigrationCompatibilityService.MigrationCompatibility migrations =
            migrationCompatibility.evaluate(release, mapping);
        checks.add(migrations.check());
        evidence.addAll(migrations.evidence());

        // 3. Compute, artifact and health dimensions from the connected runtime.
        RuntimeCompatibilityService.RuntimeCompatibility runtime =
            runtimeCompatibility.evaluate(release, mapping);
        checks.addAll(runtime.checks());
        evidence.addAll(runtime.evidence());

        return new PreflightReport(
            ReversibilityEvaluator.evaluate(release.state(), checks).status(),
            PreflightReport.verdictFor(checks),
            checks, evidence,
            PreflightReport.buildBlockerPaths(checks, evidence),
            Instant.now());
    }

    /**
     * The commit the release was observed running, from the stored
     * observation (the live runtime read does not carry source commits).
     * This is the release -> source-commit edge of the graph.
     */
    private List<ReversibilityEvidence> recordedCommitEvidence(Release release) {
        return observations.findLatestForService(release.serviceId())
            .filter(observation -> observation.identity().commitSha() != null
                && !observation.identity().commitSha().isBlank())
            .map(observation -> {
                String sha = observation.identity().commitSha();
                String shortSha = sha.substring(0, Math.min(7, sha.length()))
                    + (observation.identity().branch() == null
                        ? "" : "@" + observation.identity().branch());
                return List.of(new ReversibilityEvidence(release.candidateVersionLabel(), "source-commit",
                    shortSha, "deployment-observation",
                    "commit recorded when the deployment was observed"));
            })
            .orElseGet(List::of);
    }

    /**
     * One edge per mapped dependency, so the report shows the full chain
     * release -> repository/runtime/artifact/queue/event-bus/database/migration
     * source. Mapping evidence is what was actually bound, never inferred
     * here.
     */
    private List<ReversibilityEvidence> mappedDependencyEvidence(Release release, ServiceMapping mapping) {
        List<ReversibilityEvidence> edges = new java.util.ArrayList<>();
        for (com.rollbackshield.integrations.domain.ResourceBinding binding : mapping.bindings()) {
            String source = binding.integrationId().toString();
            try {
                source = integrations.get(binding.integrationId(), release.organizationId()).name();
            } catch (RuntimeException ignored) {
                // Integration removed after binding: keep the id as the source.
            }
            edges.add(new ReversibilityEvidence(release.candidateVersionLabel(),
                "mapped-" + binding.role().name().toLowerCase().replace('_', '-'),
                binding.externalId(), source, binding.evidence()));
        }
        return edges;
    }
}
