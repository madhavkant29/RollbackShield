package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;

import java.util.Set;

/**
 * One capability implementation within a connector family. An AWS
 * integration, for example, has several providers (ECS, ECR, SQS...) that all
 * share the family's credentials but expose different capabilities. The
 * registry never branches on provider names -- it resolves a capability to
 * whichever provider declares it.
 */
public interface CapabilityProvider {

    ConnectorType type();

    Set<ConnectorCapability> capabilities();
}
