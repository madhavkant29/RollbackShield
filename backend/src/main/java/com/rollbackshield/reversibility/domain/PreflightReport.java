package com.rollbackshield.reversibility.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The full preflight answer: the four-state status for the release, the
 * preflight verdict for "can this system return to the previous release
 * right now?", the checks, the evidence paths behind every claim, and the
 * blocker paths that answer "WHY NOT?" as structured data.
 *
 * Verdict is derived from blocker severity, never from a numeric score.
 * There is no graph database: the graph is assembled per request from live
 * evidence, and these records are its API shape.
 */
public record PreflightReport(
    ReversibilityStatus status,
    ReversibilityVerdict verdict,
    List<ReversibilityCheck> checks,
    List<ReversibilityEvidence> evidence,
    List<BlockerPath> blockerPaths,
    java.time.Instant evaluatedAt
) {

    public PreflightReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(verdict, "verdict");
        checks = List.copyOf(checks == null ? List.of() : checks);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        blockerPaths = List.copyOf(blockerPaths == null ? List.of() : blockerPaths);
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    public List<ReversibilityBlocker> blockers() {
        return checks.stream()
            .filter(check -> !check.passed())
            .map(ReversibilityCheck::blocker)
            .toList();
    }

    public static ReversibilityVerdict verdictFor(List<ReversibilityCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return ReversibilityVerdict.UNKNOWN;
        }
        boolean anyBlocking = false;
        boolean anyUnknown = false;
        for (ReversibilityCheck check : checks) {
            if (check.passed()) {
                continue;
            }
            switch (check.blocker().severity()) {
                case BLOCKING -> anyBlocking = true;
                case UNKNOWN -> anyUnknown = true;
                case REVIEW -> anyUnknown = true;
            }
        }
        if (anyBlocking) {
            return ReversibilityVerdict.CANNOT_ROLLBACK;
        }
        return anyUnknown ? ReversibilityVerdict.UNKNOWN : ReversibilityVerdict.CAN_ROLLBACK;
    }

    /**
     * Builds one structured path per failed check: the blocker plus the
     * evidence relations that belong to that dimension. The mapping is a
     * deterministic code-to-relation table -- no heuristics, no scoring.
     */
    public static List<BlockerPath> buildBlockerPaths(List<ReversibilityCheck> checks,
                                                      List<ReversibilityEvidence> evidence) {
        List<BlockerPath> paths = new ArrayList<>();
        for (ReversibilityCheck check : checks) {
            if (check.passed()) {
                continue;
            }
            Set<String> relations = relationsFor(check.blocker().code());
            List<ReversibilityEvidence> path = evidence.stream()
                .filter(edge -> relations.contains(edge.relation()))
                .toList();
            paths.add(new BlockerPath(check.blocker().code(), check.blocker().severity().name(),
                check.blocker().description(), path));
        }
        return List.copyOf(paths);
    }

    private static Set<String> relationsFor(String blockerCode) {
        return switch (blockerCode) {
            case ReversibilityBlocker.DESTRUCTIVE_DATABASE_MIGRATION,
                 ReversibilityBlocker.DATABASE_MIGRATIONS_UNREVIEWED,
                 ReversibilityBlocker.MIGRATION_ANALYSIS_UNAVAILABLE ->
                Set.of("introduced-migration-set", "destructive-change", "breaks",
                    "requires-review", "mapped-migration-source");
            case ReversibilityBlocker.ROLLBACK_ARTIFACT_MISSING,
                 ReversibilityBlocker.ARTIFACT_IDENTITY_UNKNOWN,
                 ReversibilityBlocker.ARTIFACT_REPOSITORY_NOT_MAPPED ->
                Set.of("exists-in", "missing-from", "artifact-unknown", "unmapped",
                    "mapped-artifact-repository", "rollback-target");
            case ReversibilityBlocker.NO_ROLLBACK_TARGET,
                 ReversibilityBlocker.RUNTIME_NOT_OBSERVABLE,
                 ReversibilityBlocker.NO_RUNTIME_MAPPING,
                 ReversibilityBlocker.ROLLBACK_EXECUTION_UNSUPPORTED ->
                Set.of("runs-as", "rollback-target", "rollback-execution", "source-commit",
                    "mapped-runtime");
            case ReversibilityBlocker.HEALTH_VERIFICATION_UNAVAILABLE,
                 ReversibilityBlocker.RUNTIME_HEALTH_DEGRADED ->
                Set.of("health", "mapped-runtime");
            case ReversibilityBlocker.NO_ACTIVE_CONTRACT,
                 ReversibilityBlocker.POLICY_EXPIRED,
                 ReversibilityBlocker.INCOMPATIBLE_STATE_WRITTEN,
                 ReversibilityBlocker.UNFENCED_WORK_PENDING ->
                Set.of("protected-by", "mapped-queue", "mapped-event-bus", "mapped-database");
            default -> Set.of();
        };
    }

    /** One blocker with the evidence path that explains it: WHY NOT, as data. */
    public record BlockerPath(String code, String severity, String description,
                              List<ReversibilityEvidence> path) {
        public BlockerPath {
            Objects.requireNonNull(code, "code");
            path = List.copyOf(path == null ? List.of() : path);
        }
    }
}
