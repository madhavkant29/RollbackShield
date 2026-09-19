package com.rollbackshield.integrations.domain.connector;

import java.util.List;
import java.util.Objects;

/**
 * The result of classifying one migration file. {@code unsupportedStatements}
 * is the honest list of statements the analyser could not classify -- those
 * force REQUIRES_REVIEW rather than being silently assumed safe.
 */
public record MigrationAnalysis(
    String version,
    String description,
    String path,
    MigrationClassification classification,
    List<String> findings,
    List<String> unsupportedStatements,
    boolean destructive
) {

    public MigrationAnalysis {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(classification, "classification");
        findings = List.copyOf(findings == null ? List.of() : findings);
        unsupportedStatements = List.copyOf(unsupportedStatements == null ? List.of() : unsupportedStatements);
    }

    public enum MigrationClassification {
        SAFE,
        UNSAFE,
        REQUIRES_REVIEW,
        UNKNOWN
    }

    public boolean blocksRollback() {
        return classification == MigrationClassification.UNSAFE;
    }
}
