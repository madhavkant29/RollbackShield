package com.rollbackshield.connectors.flyway.domain;

import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis;
import com.rollbackshield.integrations.domain.connector.MigrationAnalysis.MigrationClassification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic SQL migration classifier. It answers exactly one question:
 * would this candidate database evolution make the current rollback target
 * unable to operate?
 *
 * It is deliberately conservative: a statement it does not positively
 * recognize becomes REQUIRES_REVIEW, and representation changes (drops,
 * renames, type changes, NOT NULL tightening, enum mutation) are UNSAFE
 * because the previous release still depends on the old representation.
 * This is not a SQL parser and does not pretend to be one --
 * {@link MigrationAnalysis#unsupportedStatements()} records exactly what was
 * not understood, and every finding cites the statement's line.
 */
public final class MigrationAnalyzer {

    private MigrationAnalyzer() {
    }

    public static List<MigrationAnalysis> analyze(List<MigrationFile> migrations) {
        List<MigrationAnalysis> analyses = new ArrayList<>();
        for (MigrationFile migration : migrations) {
            analyses.add(analyze(migration));
        }
        return List.copyOf(analyses);
    }

    public static MigrationAnalysis analyze(MigrationFile migration) {
        List<Statement> statements = splitStatements(stripComments(migration.content()));
        List<String> findings = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        boolean destructive = false;
        boolean unsafe = false;
        boolean review = false;

        for (Statement statement : statements) {
            Classification classification = classify(statement);
            switch (classification) {
                case SAFE -> findings.add("safe: " + statement.summary());
                case UNSAFE -> {
                    unsafe = true;
                    destructive = true;
                    findings.add("unsafe: " + statement.summary()
                        + " -- previous version still depends on this representation; the rollback"
                        + " target would be unable to operate");
                }
                case REVIEW -> {
                    review = true;
                    findings.add("review: " + statement.summary()
                        + " -- cannot be proven safe for the rollback target");
                }
                case UNKNOWN -> {
                    review = true;
                    unsupported.add(statement.summary());
                    findings.add("unknown: " + statement.summary()
                        + " -- not recognized by the analyser");
                }
            }
        }

        MigrationClassification overall;
        if (statements.isEmpty()) {
            overall = MigrationClassification.UNKNOWN;
            findings.add("empty migration: no statements found");
        } else if (unsafe) {
            overall = MigrationClassification.UNSAFE;
        } else if (review) {
            overall = MigrationClassification.REQUIRES_REVIEW;
        } else {
            overall = MigrationClassification.SAFE;
        }

        return new MigrationAnalysis(migration.version(), migration.description(), migration.path(),
            overall, findings, unsupported, destructive);
    }

    private static Classification classify(Statement statement) {
        String sql = statement.normalized();

        // Tables and indexes.
        if (sql.startsWith("CREATE TABLE") || sql.startsWith("CREATE UNLOGGED TABLE")) {
            return Classification.SAFE;
        }
        if (sql.startsWith("CREATE INDEX") || sql.startsWith("CREATE UNIQUE INDEX")
            || sql.startsWith("DROP INDEX") || sql.startsWith("CREATE EXTENSION")) {
            return Classification.SAFE;
        }
        if (sql.startsWith("DROP TABLE") || sql.startsWith("DROP SCHEMA")
            || sql.startsWith("TRUNCATE") || sql.startsWith("DELETE FROM")) {
            return Classification.UNSAFE;
        }

        // Columns.
        if (sql.startsWith("ALTER TABLE") && sql.contains(" ADD COLUMN")) {
            boolean notNull = sql.contains(" NOT NULL");
            boolean hasDefault = sql.contains(" DEFAULT ");
            return notNull && !hasDefault ? Classification.UNSAFE : Classification.SAFE;
        }
        if (sql.startsWith("ALTER TABLE") && sql.contains(" DROP COLUMN")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER TABLE") && sql.contains(" RENAME ")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER TABLE") && sql.matches(".*ALTER COLUMN .* TYPE .*")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER TABLE") && sql.matches(".*ALTER COLUMN .* SET NOT NULL.*")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER TABLE") && sql.matches(".*ALTER COLUMN .* DROP NOT NULL.*")) {
            // Loosening nullability is readable by the previous version, but
            // it changes what data can now exist; review, not safe.
            return Classification.REVIEW;
        }
        if (sql.startsWith("ALTER TABLE") && sql.contains(" DROP CONSTRAINT")) {
            return Classification.REVIEW;
        }
        if (sql.startsWith("ALTER TABLE") && sql.contains(" ADD CONSTRAINT")) {
            return Classification.REVIEW;
        }
        if (sql.startsWith("ALTER TABLE")) {
            return Classification.REVIEW;
        }

        // PostgreSQL enum/user-defined type changes.
        if (sql.startsWith("CREATE TYPE")) {
            // Additive: a new type cannot break the previous release.
            return Classification.SAFE;
        }
        if (sql.startsWith("ALTER TYPE") && sql.contains(" ADD VALUE")) {
            // The new value is legal in the database, but a previous release
            // that strictly deserializes the enum may fail when it reads a
            // row carrying it. Conservative: review, never safe.
            return Classification.REVIEW;
        }
        if (sql.startsWith("ALTER TYPE") && (sql.contains(" RENAME ") || sql.contains(" ALTER ATTRIBUTE"))) {
            // Representation change: previous readers expect the old name.
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER TYPE")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("DROP TYPE")) {
            return Classification.UNSAFE;
        }
        if (sql.startsWith("ALTER DOMAIN")) {
            if (sql.contains(" NOT NULL") || sql.contains(" DROP CONSTRAINT")) {
                return Classification.UNSAFE;
            }
            return Classification.REVIEW;
        }

        // Data changes.
        if (sql.startsWith("UPDATE ")) {
            return Classification.REVIEW;
        }
        if (sql.startsWith("INSERT INTO")) {
            return Classification.SAFE;
        }

        return Classification.UNKNOWN;
    }

    static String stripComments(String content) {
        StringBuilder result = new StringBuilder(content.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;
                    result.append('\n');
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (!inString && current == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && current == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            }
            result.append(current);
        }
        return result.toString();
    }

    static List<Statement> splitStatements(String sql) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int line = 1;
        int statementStartLine = 1;
        for (int i = 0; i < sql.length(); i++) {
            char character = sql.charAt(i);
            if (character == '\n') {
                line++;
            }
            if (character == '\'') {
                inString = !inString;
            }
            if (character == ';' && !inString) {
                addStatement(statements, current, statementStartLine);
                current.setLength(0);
                statementStartLine = line;
            } else {
                current.append(character);
            }
        }
        addStatement(statements, current, statementStartLine);
        return statements;
    }

    private static void addStatement(List<Statement> statements, StringBuilder raw, int startLine) {
        String rawText = raw.toString();
        int leadingBreaks = 0;
        for (int i = 0; i < rawText.length() && Character.isWhitespace(rawText.charAt(i)); i++) {
            if (rawText.charAt(i) == '\n') {
                leadingBreaks++;
            }
        }
        String trimmed = rawText.trim();
        if (!trimmed.isEmpty()) {
            statements.add(new Statement(trimmed, startLine + leadingBreaks));
        }
    }

    /** A single statement plus its normalized form used for matching. */
    static final class Statement {
        private final String raw;
        private final String normalized;
        private final int line;

        Statement(String raw, int line) {
            this.raw = raw;
            this.line = Math.max(1, line);
            this.normalized = raw.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        }

        String normalized() {
            return normalized;
        }

        int line() {
            return line;
        }

        String summary() {
            String collapsed = raw.replaceAll("\\s+", " ").trim();
            String truncated = collapsed.length() > 140 ? collapsed.substring(0, 140) + "..." : collapsed;
            return "line " + line + ": " + truncated;
        }
    }

    enum Classification {
        SAFE,
        UNSAFE,
        REVIEW,
        UNKNOWN
    }
}
