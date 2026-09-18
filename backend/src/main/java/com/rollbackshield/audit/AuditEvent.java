package com.rollbackshield.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
    String eventId,
    String organizationId,
    String releaseId,
    String actor,
    AuditAction action,
    String target,
    Instant timestamp,
    String requestId,
    String reason,
    String previousState,
    String newState,
    Map<String, String> metadata
) {

    public AuditEvent {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(timestamp, "timestamp");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AuditEvent of(String organizationId, String releaseId, String actor,
                                 AuditAction action, String target, String reason,
                                 String previousState, String newState,
                                 Map<String, String> metadata) {
        return new AuditEvent(UUID.randomUUID().toString(), organizationId, releaseId, actor,
            action, target, Instant.now(), UUID.randomUUID().toString(), reason,
            previousState, newState, metadata);
    }
}
