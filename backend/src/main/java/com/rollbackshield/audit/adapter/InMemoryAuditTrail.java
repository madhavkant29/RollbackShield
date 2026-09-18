package com.rollbackshield.audit.adapter;

import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link AuditTrail}. Suitable for local
 * development and this hackathon's demo runs. The DynamoDB adapter
 * (DynamoDbAuditTrail) implements the exact same port, so nothing above this
 * interface needs to change when switching persistence modes.
 */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "in-memory", matchIfMissing = true)
public final class InMemoryAuditTrail implements AuditTrail {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> listForRelease(String releaseId) {
        return Collections.unmodifiableList(
            events.stream()
                .filter(e -> releaseId.equals(e.releaseId()))
                .collect(Collectors.toList())
        );
    }
}
