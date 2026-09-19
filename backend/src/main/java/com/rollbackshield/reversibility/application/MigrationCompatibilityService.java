package com.rollbackshield.reversibility.application;

import com.rollbackshield.integrations.application.ConnectorRegistry;
import com.rollbackshield.integrations.application.IntegrationApplicationService;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.integrations.domain.DeploymentObservationRepository;
import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DatabaseMigrationAnalysisPort;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis;
import com.rollbackshield.integrations.domain.connector.SourceMetadataPort;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.reversibility.domain.ReversibilityBlocker;
import com.rollbackshield.reversibility.domain.ReversibilityCheck;
import com.rollbackshield.reversibility.domain.ReversibilityEvidence;
import com.rollbackshield.shared.domain.OrganizationId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Database dimension of preflight: which migrations did the candidate
 * introduce (via a real commit range in the bound repository), and are any
 * of them destructive? Analysis is deterministic and conservative; when the
 * commit baseline is missing, the answer is UNKNOWN rather than safe.
 */
@Service
public class MigrationCompatibilityService {

    private final IntegrationApplicationService integrations;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final DeploymentObservationRepository observations;

    public MigrationCompatibilityService(IntegrationApplicationService integrations,
                                         ConnectorRegistry connectors, CredentialResolver credentials,
                                         DeploymentObservationRepository observations) {
        this.integrations = integrations;
        this.connectors = connectors;
        this.credentials = credentials;
        this.observations = observations;
    }

    public MigrationCompatibility evaluate(Release release, ServiceMapping mapping) {
        List<ReversibilityEvidence> evidence = new ArrayList<>();
        Optional<ResourceBinding> sourceBinding = mapping.firstBinding(
            ResourceBinding.BindingRole.MIGRATION_SOURCE);
        if (sourceBinding.isEmpty()) {
            evidence.add(new ReversibilityEvidence(release.candidateVersionLabel(), "has-no",
                "migration source", "service-mapping",
                "bind a repository with Flyway migrations to evaluate schema compatibility"));
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "no migration source is mapped, so database compatibility is unknown")), evidence);
        }

        ResourceBinding binding = sourceBinding.get();
        Integration sourceIntegration = integrations.get(binding.integrationId(), release.organizationId());

        SourceMetadataPort sourcePort;
        try {
            sourcePort = connectors.port(sourceIntegration.type(),
                ConnectorCapability.SOURCE_METADATA, SourceMetadataPort.class);
        } catch (RuntimeException e) {
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "migration source connector does not support source metadata: " + safeMessage(e))),
                evidence);
        }
        ConnectorContext sourceContext = new ConnectorContext(sourceIntegration,
            credentials.resolve(sourceIntegration.credential()));

        List<DeploymentObservation> serviceObservations = observations.findByService(release.serviceId());
        String candidateCommit = serviceObservations.stream()
            .map(observation -> observation.identity().commitSha())
            .filter(sha -> sha != null && !sha.isBlank())
            .findFirst().orElse(null);
        String baselineCommit = serviceObservations.stream()
            .map(observation -> observation.identity().commitSha())
            .filter(sha -> sha != null && !sha.isBlank() && !sha.equals(candidateCommit))
            .findFirst().orElse(null);
        if (candidateCommit == null || baselineCommit == null) {
            evidence.add(new ReversibilityEvidence(release.candidateVersionLabel(), "baseline",
                "unavailable", "deployment-observations",
                "no two observed commits are available to diff migrations against"));
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "no commit baseline for the previous release; migrations cannot be isolated")), evidence);
        }

        List<String> changedMigrations;
        try {
            changedMigrations = sourcePort.changedMigrationFilesBetween(sourceContext,
                binding.externalId(), baselineCommit, candidateCommit);
        } catch (RuntimeException e) {
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "migration source compare failed: " + safeMessage(e))), evidence);
        }

        evidence.add(new ReversibilityEvidence(shortSha(candidateCommit), "introduced-migration-set",
            summarizeMigrations(changedMigrations), sourceIntegration.type().name().toLowerCase()
                + ":" + binding.externalId(),
            "diff " + shortSha(baselineCommit) + "..." + shortSha(candidateCommit)));
        if (changedMigrations.isEmpty()) {
            return new MigrationCompatibility(ReversibilityCheck.pass("Database compatibility"), evidence);
        }

        List<MigrationFile> files;
        try {
            files = sourcePort.fetchMigrationFiles(sourceContext, binding.externalId(), changedMigrations);
        } catch (RuntimeException e) {
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "migration files could not be fetched: " + safeMessage(e))), evidence);
        }
        if (files.isEmpty()) {
            evidence.add(new ReversibilityEvidence(release.candidateVersionLabel(), "migrations",
                "unreadable", sourceIntegration.type().name().toLowerCase() + ":" + binding.externalId(), "changed migration files could not be read"));
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.unknown(ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE,
                    "changed migration files could not be read")), evidence);
        }

        DatabaseMigrationAnalysisPort analyzer = connectors.portForCapability(
            ConnectorCapability.DATABASE_MIGRATION_ANALYSIS, DatabaseMigrationAnalysisPort.class);
        List<MigrationAnalysis> analyses = analyzer.analyze(files);

        List<MigrationAnalysis> unsafe = analyses.stream().filter(MigrationAnalysis::blocksRollback).toList();
        List<MigrationAnalysis> review = analyses.stream()
            .filter(analysis -> analysis.classification() == MigrationAnalysis.MigrationClassification.REQUIRES_REVIEW
                || analysis.classification() == MigrationAnalysis.MigrationClassification.UNKNOWN)
            .toList();

        for (MigrationAnalysis analysis : analyses) {
            if (analysis.classification() == MigrationAnalysis.MigrationClassification.SAFE) {
                evidence.add(new ReversibilityEvidence(analysis.version(), "safe-migration",
                    analysis.path(), "flyway", String.join("; ", analysis.findings())));
            }
        }
        if (!unsafe.isEmpty()) {
            for (MigrationAnalysis analysis : unsafe) {
                evidence.add(new ReversibilityEvidence(analysis.version(), "destructive-change",
                    analysis.path(), "flyway", String.join("; ", analysis.findings())));
                evidence.add(new ReversibilityEvidence(analysis.path(), "breaks",
                    release.previousVersionLabel(), "flyway",
                    "previous version still depends on this representation"));
            }
            MigrationAnalysis first = unsafe.get(0);
            String operation = first.findings().stream()
                .filter(finding -> finding.startsWith("unsafe:"))
                .findFirst()
                .orElse("destructive migration");
            String migrationName = first.path().contains("/")
                ? first.path().substring(first.path().lastIndexOf('/') + 1) : first.path();
            String description = migrationName + ": " + operation
                + " -- " + release.previousVersionLabel()
                + " would be unable to operate (previous version still depends on this representation)";
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.blocking(ReversibilityBlocker.DESTRUCTIVE_DATABASE_MIGRATION,
                    description)), evidence);
        }
        if (!review.isEmpty()) {
            for (MigrationAnalysis analysis : review) {
                evidence.add(new ReversibilityEvidence(analysis.version(), "requires-review",
                    analysis.path(), "flyway", String.join("; ", analysis.findings())));
            }
            String versions = review.stream().map(MigrationAnalysis::version).reduce((a, b) -> a + ", " + b)
                .orElse("");
            return new MigrationCompatibility(ReversibilityCheck.fail("Database compatibility",
                ReversibilityBlocker.review(ReversibilityBlocker.DATABASE_MIGRATIONS_UNREVIEWED,
                    "candidate migrations (" + versions + ") could not be proven rollback-safe")), evidence);
        }
        return new MigrationCompatibility(ReversibilityCheck.pass("Database compatibility"), evidence);
    }

    private static String shortSha(String sha) {
        return sha == null ? "unknown" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static String summarizeMigrations(List<String> paths) {
        String joined = paths.stream()
            .map(path -> path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path)
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
        return joined.length() > 200 ? joined.substring(0, 200) + "..." : joined;
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record MigrationCompatibility(ReversibilityCheck check, List<ReversibilityEvidence> evidence) {
    }
}
