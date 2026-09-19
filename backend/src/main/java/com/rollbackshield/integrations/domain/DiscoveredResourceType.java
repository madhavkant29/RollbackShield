package com.rollbackshield.integrations.domain;

public enum DiscoveredResourceType {
    SOURCE_REPOSITORY,
    RUNTIME_CLUSTER,
    RUNTIME_SERVICE,
    RUNTIME_TASK_DEFINITION,
    KUBERNETES_DEPLOYMENT,
    ARTIFACT_REPOSITORY,
    ARTIFACT,
    QUEUE,
    EVENT_BUS,
    DATABASE,
    DATABASE_TABLE,
    LOG_GROUP
}
