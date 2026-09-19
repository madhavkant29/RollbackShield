package com.rollbackshield.integrations.domain;

/**
 * The closed set of things a connector can do. A connector declares only
 * what it implements; the registry refuses to route a capability a
 * connector did not declare, so an unimplemented operation is an explicit
 * error instead of a silent empty result.
 */
public enum ConnectorCapability {

    // Source control
    SOURCE_DISCOVERY,
    SOURCE_METADATA,

    // Deployment pipeline
    DEPLOYMENT_EVENTS,
    DEPLOYMENT_GATE,

    // Runtime
    RUNTIME_DISCOVERY,
    DEPLOYMENT_STATUS,
    ROLLBACK_EXECUTION,
    HEALTH_VERIFICATION,

    // Artifacts
    ARTIFACT_DISCOVERY,
    ARTIFACT_VERIFICATION,

    // Data
    DATABASE_DISCOVERY,
    DATABASE_MIGRATION_ANALYSIS,

    // Async / events
    QUEUE_DISCOVERY,
    QUEUE_FENCING,
    EVENT_DISCOVERY,

    // Observability
    LOG_DISCOVERY
}
