package com.rollbackshield.shared.api;

import java.time.Instant;
import java.util.Map;

/**
 * Stable structured error body (§53). The frontend switches on `code`, never
 * on `message` text.
 */
public record ApiError(
    String code,
    String message,
    Instant timestamp,
    String requestId,
    Map<String, String> details
) {
    public static ApiError of(String code, String message, String requestId, Map<String, String> details) {
        return new ApiError(code, message, Instant.now(), requestId, details == null ? Map.of() : details);
    }
}
