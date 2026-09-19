package com.rollbackshield.integrations.domain.connector;

import java.util.List;
import java.util.Objects;

/**
 * A table or view with its columns, read from the live database. Used as
 * evidence for migration compatibility questions ("does this column still
 * exist?").
 */
public record DatabaseObject(String schema, String name, String kind, List<Column> columns) {

    public DatabaseObject {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        columns = List.copyOf(columns == null ? List.of() : columns);
    }

    public record Column(String name, String dataType, boolean nullable) {
        public Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dataType, "dataType");
        }
    }

    public boolean hasColumn(String columnName) {
        return columns.stream().anyMatch(column -> column.name().equalsIgnoreCase(columnName));
    }
}
