package com.rollbackshield.integrations.domain;

import java.util.Optional;

/**
 * The connected-system types this build can actually talk to. Anything not
 * in this enum is, by definition, not implemented -- the API rejects it
 * rather than showing an aspirational provider in the UI.
 */
public enum ConnectorType {
    AWS(ConnectorCategory.CLOUD),
    GITHUB(ConnectorCategory.SOURCE_CONTROL),
    KUBERNETES(ConnectorCategory.RUNTIME),
    POSTGRESQL(ConnectorCategory.DATA),
    FLYWAY(ConnectorCategory.DATA);

    private final ConnectorCategory category;

    ConnectorType(ConnectorCategory category) {
        this.category = category;
    }

    public ConnectorCategory category() {
        return category;
    }

    public static Optional<ConnectorType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
