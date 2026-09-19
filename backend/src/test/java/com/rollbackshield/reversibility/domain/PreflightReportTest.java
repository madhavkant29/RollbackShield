package com.rollbackshield.reversibility.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the deterministic evidence-path assembly: verdict from blocker
 * severity, and one structured blocker path per failing check containing
 * only that dimension's evidence. No scoring, no heuristics.
 */
class PreflightReportTest {

    private static final ReversibilityEvidence DESTRUCTIVE = new ReversibilityEvidence(
        "V219", "destructive-change", "db/migration/V219__remove_legacy_tax_code.sql",
        "github:acme/checkout", "unsafe: line 1: ALTER TABLE orders DROP COLUMN legacy_tax_code");
    private static final ReversibilityEvidence REQUIRED_BY = new ReversibilityEvidence(
        "db/migration/V219__remove_legacy_tax_code.sql", "breaks", "checkout@task-definition:106",
        "github:acme/checkout", "previous version still depends on this representation");
    private static final ReversibilityEvidence ARTIFACT_EXISTS = new ReversibilityEvidence(
        "sha256:abc", "exists-in", "aws-hackathon", "aws:aws-hackathon", "digest verified");
    private static final ReversibilityEvidence UNRELATED = new ReversibilityEvidence(
        "checkout@task-definition:107", "mapped-queue", "payments-jobs", "aws-hackathon", "confirmed");

    @Test
    void destructiveMigrationBlockerCarriesItsEvidenceChain() {
        ReversibilityCheck database = ReversibilityCheck.fail("Database compatibility",
            ReversibilityBlocker.blocking(ReversibilityBlocker.DESTRUCTIVE_DATABASE_MIGRATION,
                "V219 drops orders.legacy_tax_code"));

        List<PreflightReport.BlockerPath> paths = PreflightReport.buildBlockerPaths(
            List.of(database), List.of(DESTRUCTIVE, REQUIRED_BY, ARTIFACT_EXISTS, UNRELATED));

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).code()).isEqualTo("DESTRUCTIVE_DATABASE_MIGRATION");
        assertThat(paths.get(0).severity()).isEqualTo("BLOCKING");
        assertThat(paths.get(0).path())
            .extracting(ReversibilityEvidence::relation)
            .containsExactlyInAnyOrder("destructive-change", "breaks");
        // Artifact and mapping evidence belong to other dimensions and are excluded.
        assertThat(paths.get(0).path())
            .noneMatch(edge -> edge.relation().equals("exists-in"))
            .noneMatch(edge -> edge.relation().equals("mapped-queue"));
    }

    @Test
    void passingChecksProduceNoBlockerPaths() {
        List<PreflightReport.BlockerPath> paths = PreflightReport.buildBlockerPaths(
            List.of(ReversibilityCheck.pass("Database compatibility"),
                ReversibilityCheck.pass("Rollback artifact availability")),
            List.of(DESTRUCTIVE, ARTIFACT_EXISTS));

        assertThat(paths).isEmpty();
    }

    @Test
    void unsupportedRollbackExecutionPathsToTheRuntimeEvidence() {
        ReversibilityCheck execution = ReversibilityCheck.fail("Rollback execution",
            ReversibilityBlocker.unknown(ReversibilityBlocker.ROLLBACK_EXECUTION_UNSUPPORTED,
                "KUBERNETES cannot execute rollbacks"));
        ReversibilityEvidence runtime = new ReversibilityEvidence(
            "payments", "rollback-execution", "unsupported", "kubernetes:cluster",
            "no rollback execution capability");

        List<PreflightReport.BlockerPath> paths = PreflightReport.buildBlockerPaths(
            List.of(execution), List.of(runtime, ARTIFACT_EXISTS));

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).severity()).isEqualTo("UNKNOWN");
        assertThat(paths.get(0).path()).containsExactly(runtime);
    }

    @Test
    void verdictComesFromBlockerSeverityNotFromCounts() {
        ReversibilityCheck review = ReversibilityCheck.fail("Database compatibility",
            ReversibilityBlocker.review(ReversibilityBlocker.DATABASE_MIGRATIONS_UNREVIEWED, "review"));
        ReversibilityCheck artifact = ReversibilityCheck.fail("Rollback artifact availability",
            ReversibilityBlocker.blocking(ReversibilityBlocker.ROLLBACK_ARTIFACT_MISSING, "gone"));

        assertThat(PreflightReport.verdictFor(List.of(review)))
            .isEqualTo(ReversibilityVerdict.UNKNOWN);
        assertThat(PreflightReport.verdictFor(List.of(review, artifact)))
            .isEqualTo(ReversibilityVerdict.CANNOT_ROLLBACK);
        assertThat(PreflightReport.verdictFor(List.of(ReversibilityCheck.pass("x"))))
            .isEqualTo(ReversibilityVerdict.CAN_ROLLBACK);
        assertThat(PreflightReport.verdictFor(List.of()))
            .isEqualTo(ReversibilityVerdict.UNKNOWN);
    }
}
