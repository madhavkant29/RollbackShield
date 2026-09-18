package com.rollbackshield.shared.events.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned domain event (§35). Consumers must tolerate duplicates -- nothing
 * here guarantees exactly-once delivery once these reach EventBridge.
 */
public record DomainEvent(
    String eventType,
    int eventVersion,
    String eventId,
    Instant occurredAt,
    String organizationId,
    String releaseId,
    Map<String, String> payload
) {
    public DomainEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(organizationId, "organizationId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static DomainEvent of(String eventType, String organizationId, String releaseId,
                                  Map<String, String> payload) {
        return new DomainEvent(eventType, 1, UUID.randomUUID().toString(), Instant.now(),
            organizationId, releaseId, payload);
    }
}
