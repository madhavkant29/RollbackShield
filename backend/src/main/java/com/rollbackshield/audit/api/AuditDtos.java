package com.rollbackshield.audit.api;

import java.time.Instant;

public final class AuditDtos {
    private AuditDtos() {
    }

    public record AuditEventResponse(String eventId, String actor, String action, String target,
                                      Instant timestamp, String reason, String previousState,
                                      String newState) {
    }
}
