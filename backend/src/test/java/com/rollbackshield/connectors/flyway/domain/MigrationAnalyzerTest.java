package com.rollbackshield.connectors.flyway.domain;

import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis.MigrationClassification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationAnalyzerTest {

    @Test
    void additiveMigrationsAreSafe() {
        assertClassified("V41__add_table.sql", "CREATE TABLE customers (id uuid primary key);",
            MigrationClassification.SAFE);
        assertClassified("V42__add_nullable.sql",
            "ALTER TABLE customers ADD COLUMN billing_address text;", MigrationClassification.SAFE);
        assertClassified("V43__add_defaulted.sql",
            "ALTER TABLE customers ADD COLUMN region varchar(64) NOT NULL DEFAULT 'us-east-1';",
            MigrationClassification.SAFE);
        assertClassified("V44__index.sql",
            "CREATE INDEX idx_customers_region ON customers(region);", MigrationClassification.SAFE);
    }

    @Test
    void dropColumnIsUnsafeAndReportedWithEvidence() {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile("V219", "drop legacy column",
            "db/migration/V219__drop_legacy_column.sql",
            "ALTER TABLE customers DROP COLUMN billing_address;"));

        assertEquals(MigrationClassification.UNSAFE, analysis.classification());
        assertTrue(analysis.destructive());
        assertTrue(analysis.blocksRollback());
        assertTrue(analysis.findings().stream().anyMatch(finding ->
            finding.contains("DROP COLUMN billing_address")));
    }

    @Test
    void dropTableRenameAlterTypeAndNotNullTighteningAreUnsafe() {
        assertClassified("V1.sql", "DROP TABLE legacy_orders;", MigrationClassification.UNSAFE);
        assertClassified("V2.sql", "ALTER TABLE customers RENAME COLUMN email TO email_address;",
            MigrationClassification.UNSAFE);
        assertClassified("V3.sql", "ALTER TABLE customers ALTER COLUMN id TYPE bigint;",
            MigrationClassification.UNSAFE);
        assertClassified("V4.sql", "ALTER TABLE customers ALTER COLUMN email SET NOT NULL;",
            MigrationClassification.UNSAFE);
        assertClassified("V5.sql", "TRUNCATE TABLE audit_log;", MigrationClassification.UNSAFE);
        assertClassified("V6.sql", "DELETE FROM sessions;", MigrationClassification.UNSAFE);
        assertClassified("V7.sql",
            "ALTER TABLE customers ADD COLUMN tenant_id uuid NOT NULL;", MigrationClassification.UNSAFE);
    }

    @Test
    void enumModificationAndDataUpdatesRequireReview() {
        assertClassified("V1.sql", "ALTER TYPE order_status ADD VALUE 'PARTIALLY_REFUNDED';",
            MigrationClassification.REQUIRES_REVIEW);
        assertClassified("V2.sql", "UPDATE customers SET region = 'us-east-1' WHERE region IS NULL;",
            MigrationClassification.REQUIRES_REVIEW);
    }

    @Test
    void commonPostgresEnumChangesAreClassifiedConservatively() {
        // Additive: a brand-new type cannot break the previous release.
        assertClassified("V1.sql", "CREATE TYPE payment_state AS ENUM ('PENDING', 'SETTLED');",
            MigrationClassification.SAFE);
        // Representation changes: previous readers expect the old name.
        assertClassified("V2.sql", "ALTER TYPE order_status RENAME VALUE 'PAID' TO 'SETTLED';",
            MigrationClassification.UNSAFE);
        assertClassified("V3.sql", "ALTER TYPE order_status RENAME TO order_workflow_status;",
            MigrationClassification.UNSAFE);
        assertClassified("V4.sql", "DROP TYPE order_status;", MigrationClassification.UNSAFE);
        assertClassified("V5.sql", "ALTER DOMAIN positive_amount SET NOT NULL;",
            MigrationClassification.UNSAFE);
        // Loosening nullability is a data-shape change: review, not safe.
        assertClassified("V6.sql", "ALTER TABLE customers ALTER COLUMN email DROP NOT NULL;",
            MigrationClassification.REQUIRES_REVIEW);
    }

    @Test
    void findingsCarryTheStatementLineAsEvidence() {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile("V20", "mixed",
            "db/migration/V20__mixed.sql",
            "CREATE TABLE a (id uuid);\n"
                + "CREATE INDEX idx_a ON a(id);\n"
                + "ALTER TABLE a DROP COLUMN id;"));

        assertTrue(analysis.findings().stream().anyMatch(finding -> finding.contains("line 1:")));
        assertTrue(analysis.findings().stream().anyMatch(finding -> finding.contains("line 2:")));
        assertTrue(analysis.findings().stream().anyMatch(finding ->
            finding.contains("line 3:") && finding.contains("DROP COLUMN id")));
        assertTrue(analysis.findings().stream().anyMatch(finding ->
            finding.contains("previous version still depends on this representation")));
    }

    @Test
    void unrecognizedStatementsAreReviewNotAssumedSafe() {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile("V9", "weird",
            "db/migration/V9__weird.sql", "GRANT SELECT ON customers TO reporting;"));

        assertEquals(MigrationClassification.REQUIRES_REVIEW, analysis.classification());
        assertEquals(1, analysis.unsupportedStatements().size());
        assertFalse(analysis.destructive());
    }

    @Test
    void commentsAndStringsDoNotConfuseTheClassifier() {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile("V10", "commented",
            "db/migration/V10__commented.sql",
            "-- DROP TABLE customers;\n"
                + "/* RENAME TABLE x TO y; */\n"
                + "INSERT INTO audit_log(message) VALUES ('DROP TABLE not a statement;');"));

        assertEquals(MigrationClassification.SAFE, analysis.classification());
    }

    @Test
    void aggregateClassificationTakesTheWorstStatement() {
        List<MigrationAnalysis> analyses = MigrationAnalyzer.analyze(List.of(
            new MigrationFile("V1", "safe", "V1.sql", "CREATE TABLE a (id uuid);"),
            new MigrationFile("V2", "unsafe", "V2.sql",
                "CREATE TABLE b (id uuid); ALTER TABLE a DROP COLUMN id;")));

        assertEquals(MigrationClassification.SAFE, analyses.get(0).classification());
        assertEquals(MigrationClassification.UNSAFE, analyses.get(1).classification());
    }

    @Test
    void emptyMigrationIsUnknownNotSafe() {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile("V11", "empty",
            "V11.sql", "   \n-- nothing here\n"));

        assertEquals(MigrationClassification.UNKNOWN, analysis.classification());
    }

    private static void assertClassified(String name, String sql, MigrationClassification expected) {
        MigrationAnalysis analysis = MigrationAnalyzer.analyze(new MigrationFile(
            name.substring(0, name.indexOf('_') < 0 ? name.length() : name.indexOf('_')),
            "test", name, sql));
        assertEquals(expected, analysis.classification(),
            () -> name + " findings=" + analysis.findings());
    }
}
