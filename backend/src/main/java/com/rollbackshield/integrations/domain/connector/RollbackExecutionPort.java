package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.RollbackTarget;

/**
 * Requests the runtime to move back to a specific revision. Implementations
 * must be idempotent: requesting the target the runtime is already on is a
 * success, not an error, so a retried orchestration is safe.
 */
public interface RollbackExecutionPort {

    RollbackExecutionResult requestRollback(ConnectorContext context, RollbackTarget target);

    RollbackExecutionResult monitorRollback(ConnectorContext context, RollbackTarget target);
}
