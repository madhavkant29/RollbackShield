package com.rollbackshield.integrations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ConnectionTestResult(boolean success, String message, Instant checkedAt,
                                   Map<String, String> details) {

    public ConnectionTestResult {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(checkedAt, "checkedAt");
        details = Map.copyOf(details == null ? Map.of() : details);
    }

    public static ConnectionTestResult ok(String message, Map<String, String> details) {
        return new ConnectionTestResult(true, message, Instant.now(), details);
    }

    public static ConnectionTestResult failed(String message, Map<String, String> details) {
        return new ConnectionTestResult(false, message, Instant.now(), details);
    }
}
