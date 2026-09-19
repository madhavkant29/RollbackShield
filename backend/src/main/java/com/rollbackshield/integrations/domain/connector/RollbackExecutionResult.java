package com.rollbackshield.integrations.domain.connector;

import java.util.Map;
import java.util.Objects;

/**
 * Result of a single rollback step. {@code step} names the operation so a
 * failure can be reported exactly; {@code state} distinguishes "accepted,
 * still progressing" from "completed" and "failed".
 */
public record RollbackExecutionResult(String step, State state, String detail,
                                      Map<String, String> providerData) {

    public RollbackExecutionResult {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(detail, "detail");
        providerData = Map.copyOf(providerData == null ? Map.of() : providerData);
    }

    public enum State {
        /** Provider accepted the request; convergence must be monitored. */
        IN_PROGRESS,
        /** The provider confirms the target revision is now serving. */
        COMPLETED,
        FAILED
    }

    public static RollbackExecutionResult inProgress(String step, String detail, Map<String, String> data) {
        return new RollbackExecutionResult(step, State.IN_PROGRESS, detail, data);
    }

    public static RollbackExecutionResult completed(String step, String detail, Map<String, String> data) {
        return new RollbackExecutionResult(step, State.COMPLETED, detail, data);
    }

    public static RollbackExecutionResult failed(String step, String detail, Map<String, String> data) {
        return new RollbackExecutionResult(step, State.FAILED, detail, data);
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }
}
