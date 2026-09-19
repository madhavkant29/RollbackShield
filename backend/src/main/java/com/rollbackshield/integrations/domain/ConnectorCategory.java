package com.rollbackshield.integrations.domain;

/**
 * Grouping used by the Integrations UI only. Capability is what the
 * application layer actually routes on -- category is presentation.
 */
public enum ConnectorCategory {
    SOURCE_CONTROL,
    CLOUD,
    RUNTIME,
    DEPLOYMENT,
    DATA,
    ASYNC_EVENTS
}
