package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.MigrationFile;

import java.util.List;

/**
 * Classifies a set of migration files for rollback safety. Deterministic:
 * the same files always produce the same classifications.
 */
public interface DatabaseMigrationAnalysisPort {

    List<MigrationAnalysis> analyze(List<MigrationFile> migrations);
}
