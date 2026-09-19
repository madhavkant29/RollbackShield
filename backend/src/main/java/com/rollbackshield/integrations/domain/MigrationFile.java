package com.rollbackshield.integrations.domain;

import java.util.Objects;

/**
 * A migration file with enough identity to cite as evidence. Content is held
 * in memory only for the duration of an analysis call.
 */
public record MigrationFile(String version, String description, String path, String content) {

    public MigrationFile {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(path, "path");
        content = content == null ? "" : content;
        description = description == null ? "" : description;
    }

    /** Flyway-style ordering: V219__name.sql sorts after V41__name.sql. */
    public int numericVersion() {
        String digits = version.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
